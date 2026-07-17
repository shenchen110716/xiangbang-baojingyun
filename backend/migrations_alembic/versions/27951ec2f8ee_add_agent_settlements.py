"""add agent settlements

Revision ID: 27951ec2f8ee
Revises: 7f0a1fa05267
Create Date: 2026-07-17 14:58:53.293862

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '27951ec2f8ee'
down_revision: Union[str, Sequence[str], None] = '7f0a1fa05267'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Append-only agent commission settlement ledger (§5.3).

    已确认结算项不得原地改写，差错通过调整项或冲正记录处理 — so items carry a
    frozen amount snapshot and corrections arrive as new rows pointing at the
    one they adjust, never as an UPDATE of a confirmed amount.

    The balance ceilings (an allocation may not exceed the payment's remaining
    balance nor the statement's unpaid balance) span tables and cannot be a
    single CHECK; they are enforced in the service inside one transaction with
    row locks. The constraints here are the ones a database can actually keep.
    """
    op.create_table(
        "agent_commission_statements",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("agent_id", sa.Integer, sa.ForeignKey("users.id"), nullable=False, index=True),
        sa.Column("statement_no", sa.String(40), nullable=False, unique=True),
        sa.Column("period_start", sa.Date, nullable=False),
        sa.Column("period_end", sa.Date, nullable=False),
        sa.Column("currency", sa.String(8), nullable=False, server_default="CNY"),
        sa.Column("total_amount", sa.Float, nullable=False, server_default="0"),
        sa.Column("status", sa.String(20), nullable=False, server_default="draft"),
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("period_end >= period_start", name="ck_statement_period"),
        sa.CheckConstraint("status IN ('draft','confirmed','partially_paid','paid','void')",
                           name="ck_statement_status"),
    )

    op.create_table(
        "agent_commission_statement_items",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("statement_id", sa.Integer, sa.ForeignKey("agent_commission_statements.id"),
                  nullable=False, index=True),
        sa.Column("source_type", sa.String(20), nullable=False, server_default="accrual"),
        sa.Column("policy_member_id", sa.Integer, sa.ForeignKey("policy_members.id"), nullable=True),
        sa.Column("plan_id", sa.Integer, sa.ForeignKey("insurance_plans.id"), nullable=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=True),
        sa.Column("period_start", sa.Date, nullable=True),
        sa.Column("period_end", sa.Date, nullable=True),
        sa.Column("amount", sa.Float, nullable=False, server_default="0"),
        # 金额快照：结算项固化佣金来源与金额，日后改产品或改佣金关系都不得改写它（§5.3）。
        sa.Column("amount_snapshot_json", sa.Text, nullable=False, server_default=""),
        sa.Column("status", sa.String(20), nullable=False, server_default="draft"),
        sa.Column("adjusts_item_id", sa.Integer,
                  sa.ForeignKey("agent_commission_statement_items.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("source_type IN ('accrual','adjustment','reversal')",
                           name="ck_item_source"),
        sa.CheckConstraint("status IN ('draft','confirmed','void')", name="ck_item_status"),
    )

    op.create_table(
        "agent_commission_payments",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("agent_id", sa.Integer, sa.ForeignKey("users.id"), nullable=False, index=True),
        sa.Column("amount", sa.Float, nullable=False, server_default="0"),
        sa.Column("channel", sa.String(30), nullable=False, server_default=""),
        sa.Column("transaction_no", sa.String(80), nullable=False, server_default=""),
        sa.Column("paid_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("voucher_url", sa.Text, nullable=False, server_default=""),
        sa.Column("created_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("amount > 0", name="ck_payment_amount"),
    )
    # 同一渠道流水号不得重复入账，否则一次付款会被记两遍。
    op.create_index("ux_payment_txn", "agent_commission_payments",
                    ["channel", "transaction_no"], unique=True,
                    sqlite_where=sa.text("transaction_no != ''"),
                    postgresql_where=sa.text("transaction_no != ''"))

    op.create_table(
        "agent_commission_payment_allocations",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("payment_id", sa.Integer, sa.ForeignKey("agent_commission_payments.id"),
                  nullable=False, index=True),
        sa.Column("statement_id", sa.Integer, sa.ForeignKey("agent_commission_statements.id"),
                  nullable=False, index=True),
        sa.Column("amount", sa.Float, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("amount > 0", name="ck_allocation_amount"),
    )
    op.create_index("ux_allocation_pair", "agent_commission_payment_allocations",
                    ["payment_id", "statement_id"], unique=True)


def downgrade() -> None:
    op.drop_index("ux_allocation_pair", table_name="agent_commission_payment_allocations")
    op.drop_table("agent_commission_payment_allocations")
    op.drop_index("ux_payment_txn", table_name="agent_commission_payments")
    op.drop_table("agent_commission_payments")
    op.drop_table("agent_commission_statement_items")
    op.drop_table("agent_commission_statements")
