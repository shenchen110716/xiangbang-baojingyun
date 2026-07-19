from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class AuditLog(Base):
    __tablename__ = "audit_logs"
    id: Mapped[int] = mapped_column(primary_key=True)
    # 机器接入调用（§7.3）没有 users 行可归属，此时为 NULL，由 detail 中的 key_id 标识。
    user_id: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    action: Mapped[str] = mapped_column(String(100))
    object_type: Mapped[str] = mapped_column(String(80))
    object_id: Mapped[str] = mapped_column(String(80), default="")
    detail: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class EnrollmentEmail(Base):
    __tablename__ = "enrollment_emails"
    id: Mapped[int] = mapped_column(primary_key=True)
    # 汇总发送（一封覆盖全部投保单位）时 enterprise_id 为空。
    enterprise_id: Mapped[int | None] = mapped_column(ForeignKey("enterprises.id"), nullable=True)
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    kind: Mapped[str] = mapped_column(String(20))
    recipient: Mapped[str] = mapped_column(String(160))
    filename: Mapped[str] = mapped_column(String(160))
    people_count: Mapped[int] = mapped_column(Integer, default=0)
    request_id: Mapped[str] = mapped_column(String(100), default="")
    status: Mapped[str] = mapped_column(String(30), default="sent")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
    # 对方回执（人工标记）：pending=待回执 / confirmed=已确认，附备注与时间。
    receipt_status: Mapped[str] = mapped_column(String(20), default="pending")
    receipt_note: Mapped[str] = mapped_column(Text, default="")
    receipt_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    receipt_by: Mapped[int | None] = mapped_column(Integer, nullable=True)
