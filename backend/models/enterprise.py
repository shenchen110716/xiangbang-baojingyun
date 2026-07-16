from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import CheckConstraint, DateTime, Float, ForeignKey, Index, Integer, String, text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class Enterprise(Base):
    __tablename__ = "enterprises"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(160), index=True)
    kind: Mapped[str] = mapped_column(String(30), default="企业")
    credit_code: Mapped[str] = mapped_column(String(40), default="")
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    agent_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    premium_balance: Mapped[float] = mapped_column(Float, default=0)
    usage_balance: Mapped[float] = mapped_column(Float, default=0)
    usage_fee_daily: Mapped[float] = mapped_column(Float, default=0.1)
    alert_days: Mapped[int] = mapped_column(Integer, default=3)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class ActualEmployer(Base):
    __tablename__ = "actual_employers"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    name: Mapped[str] = mapped_column(String(160))
    credit_code: Mapped[str] = mapped_column(String(40), default="")
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class UserEmployerScope(Base):
    __tablename__ = "user_employer_scopes"
    __table_args__ = (
        CheckConstraint(
            "responsibility_type IN ('primary', 'collaborator')",
            name="ck_scope_responsibility_type",
        ),
        CheckConstraint("status IN ('active', 'revoked')", name="ck_scope_status"),
        Index(
            "ix_scope_active_lookup",
            "user_id",
            "enterprise_id",
            "actual_employer_id",
            "status",
        ),
        Index(
            "uq_scope_live_assignment",
            "user_id",
            "actual_employer_id",
            unique=True,
            postgresql_where=text("status = 'active' AND revoked_at IS NULL"),
            sqlite_where=text("status = 'active' AND revoked_at IS NULL"),
        ),
        Index(
            "uq_scope_live_primary",
            "actual_employer_id",
            unique=True,
            postgresql_where=text(
                "responsibility_type = 'primary' AND status = 'active' AND revoked_at IS NULL"
            ),
            sqlite_where=text(
                "responsibility_type = 'primary' AND status = 'active' AND revoked_at IS NULL"
            ),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[int] = mapped_column(ForeignKey("actual_employers.id"), index=True)
    responsibility_type: Mapped[str] = mapped_column(String(20), default="collaborator")
    granted_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    assigned_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )
    revoked_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )
