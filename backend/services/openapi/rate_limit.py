"""DB 固定窗口限流（AC7）。

计数放 openapi_rate_counters 而不是进程内 dict——enroll.py 的内存限流在
多 worker/重启下形同虚设（测绘 hazard）。窗口 = epoch 秒对齐的整型
window_start；UPDATE … SET count=count+1 原子累加，未命中时 SAVEPOINT 内
INSERT，和并发首插撞唯一索引就回退重试 UPDATE。超限的调用也计数（攻击
者打爆窗口不会让计数器"歇着"），所以先加一再比较。
"""
import time
from datetime import datetime, timezone

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ...models import OpenApiRateCounter
from ...routers.openapi.common import E_RATE, OpenApiError


def enforce_rate_limit(session: Session, key_id: str, bucket: str,
                       limit: int, window_seconds: int) -> None:
    now = int(time.time())
    window_start = now - (now % window_seconds)
    where = (
        (OpenApiRateCounter.key_id == key_id)
        & (OpenApiRateCounter.bucket == bucket)
        & (OpenApiRateCounter.window_start == window_start)
    )
    result = session.execute(
        update(OpenApiRateCounter).where(where)
        .values(count=OpenApiRateCounter.count + 1))
    if result.rowcount == 0:
        try:
            with session.begin_nested():
                session.add(OpenApiRateCounter(
                    key_id=key_id, bucket=bucket,
                    window_start=window_start, count=1))
        except IntegrityError:
            session.execute(
                update(OpenApiRateCounter).where(where)
                .values(count=OpenApiRateCounter.count + 1))
    session.commit()

    count = session.scalar(select(OpenApiRateCounter.count).where(where)) or 0
    if count > limit:
        retry_after = max(window_start + window_seconds - now, 1)
        raise OpenApiError(429, E_RATE, "请求过于频繁，请稍后重试",
                           headers={"Retry-After": str(retry_after)})
