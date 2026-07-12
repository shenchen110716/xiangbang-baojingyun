from datetime import datetime, timezone

from sqlalchemy import DateTime, Float, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class InsurancePlan(Base):
    __tablename__ = "insurance_plans"
    id: Mapped[int] = mapped_column(primary_key=True)
    insurer: Mapped[str] = mapped_column(String(100))
    insurer_email: Mapped[str] = mapped_column(String(160), default="")
    name: Mapped[str] = mapped_column(String(160))
    coverage: Mapped[str] = mapped_column(Text, default="")
    occupation_classes: Mapped[str] = mapped_column(String(100), default="1-4类")
    price: Mapped[float] = mapped_column(Float, default=0)
    commission_rate: Mapped[float] = mapped_column(Float, default=0)
    profit_amount: Mapped[float] = mapped_column(Float, default=0)
    payment_mode: Mapped[str] = mapped_column(String(30), default="企业直投")
    billing_mode: Mapped[str] = mapped_column(String(20), default="monthly")
    effective_mode: Mapped[str] = mapped_column(String(20), default="next_day")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class PlanTier(Base):
    __tablename__ = "plan_tiers"
    id: Mapped[int] = mapped_column(primary_key=True)
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    occupation_class: Mapped[str] = mapped_column(String(30))
    price: Mapped[float] = mapped_column(Float, default=0)
    coverage: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
