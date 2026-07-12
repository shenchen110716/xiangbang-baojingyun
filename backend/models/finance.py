from datetime import datetime, timezone

from sqlalchemy import DateTime, Float, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class AgentCommission(Base):
    __tablename__ = "agent_commissions"
    id: Mapped[int] = mapped_column(primary_key=True)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    plan_id: Mapped[int] = mapped_column(ForeignKey("insurance_plans.id"))
    rate: Mapped[float] = mapped_column(Float, default=.15)
    mode: Mapped[str] = mapped_column(String(20), default="rebate")
    markup_amount: Mapped[float] = mapped_column(Float, default=0)
    sale_price: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class PaymentRecord(Base):
    __tablename__ = "payment_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    order_no: Mapped[str] = mapped_column(String(100), unique=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20), default="premium")
    amount: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(30), default="pending")
    provider: Mapped[str] = mapped_column(String(60), default="payment")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


class Invoice(Base):
    __tablename__ = "invoices"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20), default="premium")
    amount: Mapped[float] = mapped_column(Float, default=0)
    title: Mapped[str] = mapped_column(String(160), default="")
    tax_no: Mapped[str] = mapped_column(String(40), default="")
    email: Mapped[str] = mapped_column(String(160), default="")
    status: Mapped[str] = mapped_column(String(30), default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
