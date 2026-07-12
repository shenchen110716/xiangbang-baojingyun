from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class AuditLog(Base):
    __tablename__ = "audit_logs"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(100))
    object_type: Mapped[str] = mapped_column(String(80))
    object_id: Mapped[str] = mapped_column(String(80), default="")
    detail: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class EnrollmentEmail(Base):
    __tablename__ = "enrollment_emails"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    kind: Mapped[str] = mapped_column(String(20))
    recipient: Mapped[str] = mapped_column(String(160))
    filename: Mapped[str] = mapped_column(String(160))
    people_count: Mapped[int] = mapped_column(Integer, default=0)
    request_id: Mapped[str] = mapped_column(String(100), default="")
    status: Mapped[str] = mapped_column(String(30), default="sent")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
