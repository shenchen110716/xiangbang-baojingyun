from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Optional

from sqlalchemy import (
    CheckConstraint, Date, DateTime, Float, ForeignKey, Index, Numeric, String, Text, text,
)
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
    account_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurer_accounts.id"), nullable=True)
    idempotency_key: Mapped[str] = mapped_column(String(120), default="", unique=False, index=True)
    created_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))


# --- v4.2 §5.3 业务员佣金结算账本（只追加） -----------------------------
# 已确认结算项不得原地改写，差错通过调整项或冲正记录处理。结算项固化金额快照，
# 日后改产品或改佣金关系都不得改写历史结算。

class AgentCommissionStatement(Base):
    __tablename__ = "agent_commission_statements"
    __table_args__ = (
        CheckConstraint("period_end >= period_start", name="ck_statement_period"),
        CheckConstraint("status IN ('draft','confirmed','partially_paid','paid','void')",
                        name="ck_statement_status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    statement_no: Mapped[str] = mapped_column(String(40), unique=True)
    period_start: Mapped[date] = mapped_column(Date)
    period_end: Mapped[date] = mapped_column(Date)
    currency: Mapped[str] = mapped_column(String(8), default="CNY")
    total_amount: Mapped[float] = mapped_column(Float, default=0)
    status: Mapped[str] = mapped_column(String(20), default="draft")
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class AgentCommissionStatementItem(Base):
    __tablename__ = "agent_commission_statement_items"
    __table_args__ = (
        CheckConstraint("source_type IN ('accrual','adjustment','reversal')",
                        name="ck_item_source"),
        CheckConstraint("status IN ('draft','confirmed','void')", name="ck_item_status"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    statement_id: Mapped[int] = mapped_column(
        ForeignKey("agent_commission_statements.id"), index=True)
    source_type: Mapped[str] = mapped_column(String(20), default="accrual")
    policy_member_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("policy_members.id"), nullable=True)
    plan_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurance_plans.id"), nullable=True)
    enterprise_id: Mapped[Optional[int]] = mapped_column(ForeignKey("enterprises.id"), nullable=True)
    period_start: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    period_end: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    amount: Mapped[float] = mapped_column(Float, default=0)
    amount_snapshot_json: Mapped[str] = mapped_column(Text, default="")
    status: Mapped[str] = mapped_column(String(20), default="draft")
    # 冲正/调整指回它修正的那一条；原条目本身永不被改写。
    adjusts_item_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("agent_commission_statement_items.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class AgentCommissionPayment(Base):
    __tablename__ = "agent_commission_payments"
    __table_args__ = (
        CheckConstraint("amount > 0", name="ck_payment_amount"),
        Index("ux_payment_txn", "channel", "transaction_no", unique=True,
              sqlite_where=text("transaction_no != ''"),
              postgresql_where=text("transaction_no != ''")),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    amount: Mapped[float] = mapped_column(Float, default=0)
    channel: Mapped[str] = mapped_column(String(30), default="")
    transaction_no: Mapped[str] = mapped_column(String(80), default="")
    paid_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    voucher_url: Mapped[str] = mapped_column(Text, default="")
    created_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class AgentCommissionPaymentAllocation(Base):
    """一张结算单可分次付款，一次付款可覆盖多张结算单（§5.3）。"""
    __tablename__ = "agent_commission_payment_allocations"
    __table_args__ = (
        CheckConstraint("amount > 0", name="ck_allocation_amount"),
        Index("ux_allocation_pair", "payment_id", "statement_id", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    payment_id: Mapped[int] = mapped_column(
        ForeignKey("agent_commission_payments.id"), index=True)
    statement_id: Mapped[int] = mapped_column(
        ForeignKey("agent_commission_statements.id"), index=True)
    amount: Mapped[float] = mapped_column(Float)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
