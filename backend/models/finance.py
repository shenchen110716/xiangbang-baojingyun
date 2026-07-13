from datetime import datetime, timezone
from decimal import Decimal
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, Numeric, String
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


class LedgerEntry(Base):
    # SYSTEM-DESIGN-V4.md v4.1 section 7.5: single-account append-only
    # ledger (no cross-account double-entry balancing required at this
    # stage). enterprise_id + account together identify "the account" —
    # the standalone Account entity from the full design doc is deferred
    # until the broader Tenant model lands; Enterprise already carries
    # premium_balance/usage_balance as the cached BalanceSnapshot, and
    # every write path that changes one of those columns must also insert
    # a matching LedgerEntry row in the same DB transaction.
    __tablename__ = "ledger_entries"
    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"))
    account: Mapped[str] = mapped_column(String(20))  # premium / usage
    direction: Mapped[str] = mapped_column(String(10))  # credit / debit
    amount: Mapped[Decimal] = mapped_column(Numeric(18, 2))
    business_type: Mapped[str] = mapped_column(String(40))
    business_id: Mapped[str] = mapped_column(String(80), default="")
    idempotency_key: Mapped[str] = mapped_column(String(120), default="", unique=False, index=True)
    created_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
