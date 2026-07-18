"""add invoice type

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5f6
Create Date: 2026-07-18 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b2c3d4e5f6a7'
down_revision: Union[str, Sequence[str], None] = 'a1b2c3d4e5f6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """票类（增值税普通发票/专用发票），字符串默认值对 PostgreSQL 安全。
    幂等：SQLite 本地由运行时桥已加过该列时跳过（生产 PG 不跑桥，正常新增）。"""
    cols = [c["name"] for c in sa.inspect(op.get_bind()).get_columns("invoices")]
    if "invoice_type" not in cols:
        op.add_column("invoices", sa.Column("invoice_type", sa.String(length=40), nullable=False, server_default="增值税普通发票"))


def downgrade() -> None:
    op.drop_column("invoices", "invoice_type")
