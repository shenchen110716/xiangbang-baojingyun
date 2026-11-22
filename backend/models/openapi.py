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


class ExportTask(Base):
    """异步导出任务（story-p1-readonly AC1/AC7）。

    params_json 固化入队时的过滤条件与脱敏口径（sensitive 由入队那一刻
    key 的 scope 决定）；file_url 是 core/storage 的存储标记，对外只经
    短时签名 URL 下发。
    """
    __tablename__ = "export_tasks"
    __table_args__ = (
        Index("ix_export_tasks_status", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    key_id: Mapped[str] = mapped_column(String(32), default="")
    task_type: Mapped[str] = mapped_column(String(40), default="insured_persons")
    params_json: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending|processing|done|failed
    file_url: Mapped[str] = mapped_column(Text, default="")
    error: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class OpenApiDeclaration(Base):
    """写入面申报单（story-p2-write AC3/AC7）：participation 异步受理回执。

    状态机：accepted（受理落库瞬间）→ processing（存在待人工确认项）→
    succeeded / partial_succeeded / failed（全部项终态后翻转，由
    PATCH /api/insured/{id}/status 的人工确认驱动，不承诺同步生效）。
    """
    __tablename__ = "openapi_declarations"
    __table_args__ = (
        Index("ix_openapi_declarations_status", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    key_id: Mapped[str] = mapped_column(String(32), default="")
    kind: Mapped[str] = mapped_column(String(20))  # enrollment | termination
    status: Mapped[str] = mapped_column(String(20), default="accepted")
    item_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class OpenApiDeclarationItem(Base):
    """申报单逐人明细。受理即拒的项直接落 rejected+reason；受理项 pending，
    由管理员确认（insured_status 翻转）推进到 succeeded / rejected。
    external_person_id 原样存储并在明细中回传（AC5）。"""
    __tablename__ = "openapi_declaration_items"
    __table_args__ = (
        Index("ix_openapi_declaration_items_person_status", "person_id", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    declaration_id: Mapped[int] = mapped_column(
        ForeignKey("openapi_declarations.id"), index=True)
    # 防御性冗余：事件/明细查询显式按企业过滤（红线），不只信 join。
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    person_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    external_person_id: Mapped[str] = mapped_column(String(64), default="")
    name: Mapped[str] = mapped_column(String(80), default="")
    id_number: Mapped[str] = mapped_column(String(40), default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending|succeeded|rejected
    reason: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    finished_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class OpenApiWebhookEndpoint(Base):
    """企业注册的 webhook 回调地址（AC12）。key_id 记录注册时所用密钥——
    投递签名用这把密钥的 AppSecret（对方用自己手里的 secret 验签）。"""
    __tablename__ = "openapi_webhook_endpoints"

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    url: Mapped[str] = mapped_column(Text)
    key_id: Mapped[str] = mapped_column(String(32), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")  # active|disabled
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class OpenApiWebhookDelivery(Base):
    """webhook outbox（AC13）：事件与业务写同一事务落库，后台循环投递。

    endpoint_id 不设 FK：端点删除后历史投递行保留为审计痕迹，投递时查不到
    端点即置 failed。退避 1m/5m/30m/2h/12h，超过重试上限置 failed，
    admin 可手动重发。"""
    __tablename__ = "openapi_webhook_deliveries"
    __table_args__ = (
        Index("ix_openapi_webhook_deliveries_status", "status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    endpoint_id: Mapped[int] = mapped_column(Integer, index=True)
    event_id: Mapped[str] = mapped_column(String(32), index=True)
    event_type: Mapped[str] = mapped_column(String(40))
    payload: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")  # pending|delivered|failed
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    next_retry_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    last_error: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    delivered_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class OpenApiIdempotencyKey(Base):
    """写接口幂等键（AC2）：(key_id, idem_key) 唯一；request_hash 识别
    「同 key 不同体」409；response_snapshot 为空表示首次请求仍在进行中
    （或已失败被清理前的残留）；7 天后由后台循环清理（Hazard 6）。"""
    __tablename__ = "openapi_idempotency_keys"
    __table_args__ = (
        Index("ux_openapi_idempotency_key", "key_id", "idem_key", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    key_id: Mapped[str] = mapped_column(String(32))
    idem_key: Mapped[str] = mapped_column(String(128))
    request_hash: Mapped[str] = mapped_column(String(64), default="")
    response_snapshot: Mapped[str] = mapped_column(Text, default="")
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
