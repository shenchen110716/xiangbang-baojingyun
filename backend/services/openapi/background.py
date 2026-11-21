"""统一后台任务循环（AC13）+ 清理任务（AC14 + review-p0 M2）。

本部署没有 Redis/Celery（timeliness_recalc.py 的 "no scheduler in this
deployment" 先例），所以用进程内 asyncio 循环：lifespan 启动一个 30s tick
的 task。生产可能多 worker/多实例，PostgreSQL 上每个 tick 先抢
pg_try_advisory_lock——抢不到就跳过本轮，保证清理类任务全局单实例执行；
SQLite（本地/测试）没有 advisory lock，直接执行。

advisory lock 是**连接级**的（review-p0 M1）：绝不能在 ORM Session 上
执行 lock/unlock——Session 每次 commit 后连接回池，unlock 会落到另一条
连接上（返回 false、锁泄漏在原连接上）。AdvisoryLock 用 engine.connect()
单独持有一条裸连接，lock 与 unlock 保证发生在同一条连接上，tick 期间该
连接不参与 Session 池；unlock 失败时 invalidate 掉这条连接（物理断开即
由 PG 服务端释放锁），绝不带锁回池。

任务通过 register_periodic_job(name, fn) 注册，fn(session) 同步执行、
每个任务独立 try/except + logger.exception 留痕（review-p0 M4）——一个
任务炸了不拖垮整个循环，也不允许静默失败数月无人察觉。后续 story 的
webhook 重试、对账等任务挂进来即可，不再各自起线程。
"""
import asyncio
import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

from sqlalchemy.orm import Session

from ...core.db import SessionLocal, engine
from ...models import IntegrationNonce, OpenApiRateCounter, OpenApiRequestLog

logger = logging.getLogger("backend.openapi")

TICK_SECONDS = 30
NONCE_RETENTION_DAYS = 7      # 远大于 ±300s 签名窗口，重放判定不受影响
LOG_RETENTION_DAYS = 180      # 设计方案 §6：请求日志保留 180 天
RATE_COUNTER_RETENTION_SECONDS = 86400   # 限流窗口行保留 1 天（review-p0 M2）
# 任意固定 bigint；全平台这一把锁保证同刻只有一个实例跑 tick。
ADVISORY_LOCK_KEY = 823011901

_JOBS: dict[str, Callable[[Session], None]] = {}


def register_periodic_job(name: str, fn: Callable[[Session], None]) -> None:
    _JOBS[name] = fn


def registered_job_names() -> list[str]:
    return list(_JOBS)


class AdvisoryLock:
    """连接钉住的 PG advisory lock；非 PG 方言恒放行（no-op）。

    lock/unlock 必须在同一条裸连接上执行；engine_ 可注入（测试用假引擎
    验证同连接语义）。
    """

    def __init__(self, engine_=None):
        self._engine = engine_ if engine_ is not None else engine
        self._conn = None

    @property
    def held(self) -> bool:
        return self._conn is not None

    def acquire(self) -> bool:
        if self._engine.dialect.name != "postgresql":
            return True
        conn = self._engine.connect()
        try:
            got = bool(conn.exec_driver_sql(
                f"SELECT pg_try_advisory_lock({ADVISORY_LOCK_KEY})").scalar())
        except Exception:
            conn.close()
            raise
        if not got:
            conn.close()
            return False
        self._conn = conn
        return True

    def release(self) -> None:
        if self._conn is None:
            return
        conn, self._conn = self._conn, None
        try:
            conn.exec_driver_sql(
                f"SELECT pg_advisory_unlock({ADVISORY_LOCK_KEY})")
            conn.close()
        except Exception:
            # 解锁失败绝不能把仍持锁的连接还回池——作废它，物理断开由
            # PG 服务端回收锁。
            logger.exception("openapi advisory unlock failed; invalidating connection")
            conn.invalidate()


def run_tick_once(session: Session) -> list[str]:
    """跑一轮所有注册任务；返回成功执行的任务名（测试/诊断用）。"""
    ran = []
    for name, fn in list(_JOBS.items()):
        try:
            fn(session)
            session.commit()
            ran.append(name)
        except Exception:
            session.rollback()
            logger.exception("openapi background job %s failed", name)
    return ran


def run_locked_tick(engine_=None) -> Optional[list[str]]:
    """抢锁 → 独立 Session 跑一轮 → 同连接解锁。抢不到锁返回 None。"""
    lock = AdvisoryLock(engine_)
    if not lock.acquire():
        return None
    try:
        with SessionLocal() as session:
            return run_tick_once(session)
    finally:
        lock.release()


def _cleanup_integration_nonces(session: Session) -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(days=NONCE_RETENTION_DAYS)
    session.query(IntegrationNonce).filter(
        IntegrationNonce.seen_at < cutoff).delete(synchronize_session=False)


def _cleanup_openapi_request_logs(session: Session) -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(days=LOG_RETENTION_DAYS)
    session.query(OpenApiRequestLog).filter(
        OpenApiRequestLog.created_at < cutoff).delete(synchronize_session=False)


def _cleanup_openapi_rate_counters(session: Session) -> None:
    cutoff = int(time.time()) - RATE_COUNTER_RETENTION_SECONDS
    session.query(OpenApiRateCounter).filter(
        OpenApiRateCounter.window_start < cutoff).delete(synchronize_session=False)


register_periodic_job("cleanup_integration_nonces", _cleanup_integration_nonces)
register_periodic_job("cleanup_openapi_request_logs", _cleanup_openapi_request_logs)
register_periodic_job("cleanup_openapi_rate_counters", _cleanup_openapi_rate_counters)


# P1：异步导出执行任务挂进同一循环（export_tasks 模块底部自注册；放文件
# 末尾 import 是为了避免 export_tasks ↔ background 的导入期循环）。
from . import export_tasks as _export_tasks  # noqa: E402,F401  (job registration)
# P2：webhook 投递/余额预警扫描 + 幂等键清理（同样文件末尾自注册）。
from . import webhook_outbox as _webhook_outbox  # noqa: E402,F401  (job registration)
from . import idempotency as _idempotency  # noqa: E402,F401  (job registration)


async def background_loop(stop_event: asyncio.Event) -> None:
    """lifespan 启动的常驻循环：每 30s 一个 tick，stop_event 置位即退出。"""
    while not stop_event.is_set():
        try:
            # review-p2 B1：tick 里是同步 DB + 出网 HTTP（webhook 投递，单条
            # 超时 5s、每轮最多 20 条），绝不能直接在事件循环里跑——会把
            # 整个 FastAPI 卡死最长 ~100s。丢到线程池，事件循环保持可响应。
            await asyncio.to_thread(run_locked_tick)
        except Exception:
            # tick 级兜底：连 DB 连接都拿不到（如 DB 闪断）也不退出循环。
            logger.exception("openapi background tick failed")
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=TICK_SECONDS)
        except asyncio.TimeoutError:
            continue
