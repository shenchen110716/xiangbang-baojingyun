"""wechat pay fields

Revision ID: b7c8d9e0f1a2
Revises: e5f6a7b8c9d0
Create Date: 2026-07-20 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b7c8d9e0f1a2'
down_revision: Union[str, Sequence[str], None] = 'e5f6a7b8c9d0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """微信商户号收款：payment_records 加支付渠道/openid/微信交易号/支付时间，
    users 加绑定的微信 openid。均为新增可空列，幂等（列已存在则跳过）。"""
    payment_columns = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("payment_records")}
    if "channel" not in payment_columns:
        op.add_column("payment_records", sa.Column("channel", sa.String(length=20), nullable=False, server_default="native"))
    if "openid" not in payment_columns:
        op.add_column("payment_records", sa.Column("openid", sa.String(length=64), nullable=True))
    if "provider_trade_no" not in payment_columns:
        op.add_column("payment_records", sa.Column("provider_trade_no", sa.String(length=80), nullable=True))
    if "paid_at" not in payment_columns:
        op.add_column("payment_records", sa.Column("paid_at", sa.DateTime(), nullable=True))

    user_columns = {c["name"] for c in sa.inspect(op.get_bind()).get_columns("users")}
    if "wx_openid" not in user_columns:
        op.add_column("users", sa.Column("wx_openid", sa.String(length=64), nullable=True))
        op.create_unique_constraint("uq_users_wx_openid", "users", ["wx_openid"])


def downgrade() -> None:
    op.drop_constraint("uq_users_wx_openid", "users", type_="unique")
    op.drop_column("users", "wx_openid")
    op.drop_column("payment_records", "paid_at")
    op.drop_column("payment_records", "provider_trade_no")
    op.drop_column("payment_records", "openid")
    op.drop_column("payment_records", "channel")
