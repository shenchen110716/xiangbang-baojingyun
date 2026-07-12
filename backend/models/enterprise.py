from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, Integer, String
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
