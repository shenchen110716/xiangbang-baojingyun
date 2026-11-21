"""写接口幂等键（story-p2-write AC2）。

claim-first 模式：进入业务逻辑前先把 (key_id, idem_key) 占位行独立提交——
并发重放靠 ux_openapi_idempotency_key 唯一索引裁决，谁插入成功谁执行业务。

- 缺 Idempotency-Key 头 → 422/42200（写接口强制要求，防客户端超时重试双写）。
- 同 key 同 body：有快照 → 回放首次响应，不重复执行副作用；快照还没写
  （首次请求仍在跑）→ 409「处理中」。
- 同 key 不同 body → 409（防止客户端拿旧 key 发新单，静默吞掉第二单）。
- 业务失败 → release() 删占位行，允许修正后用同一 key 重试（不删会留下
  空快照行，把客户端永久锁死在 409）。
- 7 天后由后台循环清理（重试窗口远小于 7 天；无限留存只会让唯一索引膨胀）。

request_hash 覆盖 method + path + 原始 body——同一 key 打到不同端点也算
「不同请求」，一律 409。
"""
import hashlib
import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Request
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ...core.db import SessionLocal
from ...models import OpenApiIdempotencyKey
from .background import register_periodic_job

# 注意：本模块经 background.py 文件末尾自注册，而 app.py 在挂载路由**之前**
# 就 import background——此刻 routers/openapi 包尚未初始化，顶层 import
# routers.openapi.common / .auth 会形成导入期循环（declarations 路由反过来
# import 本模块时拿到半成品）。统一包异常改为函数内延迟 import。

logger = logging.getLogger("wuyoubao.openapi")

IDEMPOTENCY_HEADER = "Idempotency-Key"
RETENTION_DAYS = 7


@dataclass
class IdempotencyContext:
    key_id: str
    idem_key: str
    replayed: bool = False
    snapshot: Optional[dict] = field(default=None)

    def save(self, session: Session, data: dict) -> None:
        """业务成功后固化响应快照；跟业务数据同一个 session 提交，快照与
        副作用一起生效（分开提交会出现「副作用在、快照不在」的 409 死区）。
        review-p2 M3：业务 service（declarations/recharges）只 flush 不
        commit，这里的 commit 是整条写请求唯一的一次提交——中间任何一步
        崩溃，副作用与快照一起回滚，release() 删占位行后同 key 可重试。"""
        row = session.query(OpenApiIdempotencyKey).filter_by(
            key_id=self.key_id, idem_key=self.idem_key).first()
        if row is not None:
            row.response_snapshot = json.dumps(data, ensure_ascii=False)
        session.commit()

    def release(self) -> None:
        """业务失败时删掉占位行，允许同一 key 重试。用独立 session——业务
        session 此刻可能已经 rollback 不可用。"""
        try:
            with SessionLocal() as cleanup:
                cleanup.query(OpenApiIdempotencyKey).filter_by(
                    key_id=self.key_id, idem_key=self.idem_key,
                    response_snapshot="").delete()
                cleanup.commit()
        except Exception:  # pragma: no cover - 清理失败不掩盖业务异常
            logger.exception("idempotency release failed key=%s", self.key_id)


async def require_idempotency(principal, request: Request) -> IdempotencyContext:
    """principal: services.openapi.auth.OpenApiPrincipal（鉴权依赖已产出）。"""
    from ...routers.openapi.common import E_BIZ, OpenApiError
    idem_key = (request.headers.get(IDEMPOTENCY_HEADER) or "").strip()
    if not idem_key:
        raise OpenApiError(422, E_BIZ, "缺少 Idempotency-Key 请求头")
    if len(idem_key) > 128:
        raise OpenApiError(422, E_BIZ, "Idempotency-Key 过长（至多 128 字符）")
    body = await request.body()
    request_hash = hashlib.sha256(
        b"\n".join([request.method.encode(), request.url.path.encode(), body])
    ).hexdigest()

    # 占位行独立提交（不污染业务 session 的事务）。
    with SessionLocal() as claim_session:
        try:
            claim_session.add(OpenApiIdempotencyKey(
                key_id=principal.key_id, idem_key=idem_key,
                request_hash=request_hash))
            claim_session.commit()
            return IdempotencyContext(key_id=principal.key_id, idem_key=idem_key)
        except IntegrityError:
            claim_session.rollback()
        row = claim_session.query(OpenApiIdempotencyKey).filter_by(
            key_id=principal.key_id, idem_key=idem_key).first()
    if row is None:
        # 占位行刚被并发 release；让客户端原样重试即可。
        raise OpenApiError(409, E_BIZ, "相同 Idempotency-Key 的请求正在处理中，请稍后重试")
    if row.request_hash != request_hash:
        raise OpenApiError(409, E_BIZ,
                           "该 Idempotency-Key 已用于不同的请求，请更换新的 Idempotency-Key")
    if row.response_snapshot:
        return IdempotencyContext(
            key_id=principal.key_id, idem_key=idem_key, replayed=True,
            snapshot=json.loads(row.response_snapshot))
    raise OpenApiError(409, E_BIZ, "相同 Idempotency-Key 的请求正在处理中，请稍后重试")


def cleanup_idempotency_keys(session: Session) -> None:
    """幂等键 7 天后清理（AC2）；commit 由 run_tick_once 统一做。"""
    cutoff = datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)
    session.query(OpenApiIdempotencyKey).filter(
        OpenApiIdempotencyKey.created_at < cutoff).delete(synchronize_session=False)


register_periodic_job("openapi_idempotency_cleanup", cleanup_idempotency_keys)
