"""enrollment email data_date

Revision ID: e5f6a7b8c9d0
Revises: d4e5f6a7b8c9
Create Date: 2026-07-19 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'e5f6a7b8c9d0'
down_revision: Union[str, Sequence[str], None] = 'd4e5f6a7b8c9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """记录本封邮件发送的参停保数据所属日期。幂等：列已存在则跳过。"""
    cols = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("enrollment_emails")}
    if "data_date" not in cols:
        op.add_column("enrollment_emails", sa.Column("data_date", sa.String(length=20), nullable=False, server_default=""))


def downgrade() -> None:
    op.drop_column("enrollment_emails", "data_date")
