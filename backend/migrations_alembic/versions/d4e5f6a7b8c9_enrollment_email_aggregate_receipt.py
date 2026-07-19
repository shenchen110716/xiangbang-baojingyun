"""enrollment email aggregate + receipt

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-07-19 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd4e5f6a7b8c9'
down_revision: Union[str, Sequence[str], None] = 'c3d4e5f6a7b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """汇总发送（enterprise_id 可空）+ 对方回执字段。幂等：列已存在则跳过。"""
    cols = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("enrollment_emails")}
    if "receipt_status" not in cols:
        op.add_column("enrollment_emails", sa.Column("receipt_status", sa.String(length=20), nullable=False, server_default="pending"))
    if "receipt_note" not in cols:
        op.add_column("enrollment_emails", sa.Column("receipt_note", sa.Text(), nullable=False, server_default=""))
    if "receipt_at" not in cols:
        op.add_column("enrollment_emails", sa.Column("receipt_at", sa.DateTime(), nullable=True))
    if "receipt_by" not in cols:
        op.add_column("enrollment_emails", sa.Column("receipt_by", sa.Integer(), nullable=True))
    # enterprise_id 放开为可空（PostgreSQL）。SQLite 无需改动列可空性。
    if op.get_bind().dialect.name == "postgresql":
        op.alter_column("enrollment_emails", "enterprise_id", existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    for col in ("receipt_by", "receipt_at", "receipt_note", "receipt_status"):
        op.drop_column("enrollment_emails", col)
