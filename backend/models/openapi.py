"""开放平台（/openapi/v1）P0 地基的三张表（story-p0-foundation AC1）。

与 §7.3 的 integration_api_keys 并存而不复用：旧表服务 v1 用工事件回传，
这套表服务对外开放平台（v2 签名、scope、限流、审计、沙箱），二者密钥
生命周期与权限模型不同，混在一张表里会迫使 v1 端点跟着 v2 的 status/
expires/env 语义走，反而破坏「v1 不回归」的红线。

secret 与 v1 同一先例：Fernet 加密存储（core/id_number.py 的
encrypt_bytes，密钥派生自 ID_ENCRYPTION_KEY）——签名验证需要还原明文，
单向哈希在 HMAC 方案下不可用。secondary_secret_encrypted 是轮换宽限期
的旧 secret（P0 只建列，轮换端点属后续 story）。
"""
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, Text

from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


def _now() -> datetime:
    return datetime.now(timezone.utc)


class OpenApiClient(Base):
    """开放平台接入方（一把 AppKey/AppSecret）。"""
    __tablename__ = "openapi_clients"

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    name: Mapped[str] = mapped_column(String(64), default="")
    key_id: Mapped[str] = mapped_column(String(32), unique=True)
    secret_encrypted: Mapped[str] = mapped_column(Text)
    secondary_secret_encrypted: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    rotated_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    # 逗号分隔的 scope 清单；空 = 无任何 scope（显式授予，拒绝默认放大）。
    scopes: Mapped[str] = mapped_column(Text, default="")
    # 逗号分隔实际工作单位 id；空 = 企业全部（沿用 v1 语义）。
    allowed_employer_ids: Mapped[str] = mapped_column(Text, default="")
    # 逗号分隔 CIDR/IP；空 = 不限来源。
    ip_allowlist: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(20), default="active")  # active|disabled
    expires_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    env: Mapped[str] = mapped_column(String(10), default="prod")  # prod|sandbox
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class OpenApiRequestLog(Base):
    """每次 /openapi/v1 调用（含失败）一行；180 天后由后台任务清理。"""
    __tablename__ = "openapi_request_logs"
    __table_args__ = (
        Index("ix_openapi_request_logs_created_at", "created_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    request_id: Mapped[str] = mapped_column(String(32), index=True)
    # 认证失败时可能只有调用方声称的 key_id，甚至没有；都要留痕。
    key_id: Mapped[Optional[str]] = mapped_column(String(32), nullable=True, index=True)
    enterprise_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True, index=True)
    method: Mapped[str] = mapped_column(String(10))
    path: Mapped[str] = mapped_column(String(200))
    status: Mapped[int] = mapped_column(Integer)
    latency_ms: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    ip: Mapped[str] = mapped_column(String(64), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class OpenApiRateCounter(Base):
    """固定窗口限流计数（存 DB 而非进程内存：enroll.py 的进程内 dict 在
    多 worker 下形同虚设，是测绘明确的 hazard）。window_start 用整型
    epoch 秒，PG/SQLite 同语义，UPDATE … count=count+1 原子累加。"""
    __tablename__ = "openapi_rate_counters"
    __table_args__ = (
        Index("ux_rate_counter_window", "key_id", "bucket", "window_start", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    key_id: Mapped[str] = mapped_column(String(32))
    bucket: Mapped[str] = mapped_column(String(32), default="default")
    window_start: Mapped[int] = mapped_column(Integer)
    count: Mapped[int] = mapped_column(Integer, default=0)
