"""add pending_terminations table

Revision ID: f7e2d9b1a4c8
Revises: c3e7aebc5c9a
Create Date: 2026-07-16
"""
from alembic import op
import sqlalchemy as sa

revision = "f7e2d9b1a4c8"
down_revision = "c3e7aebc5c9a"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "pending_terminations",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("enterprise_id", sa.Integer(), sa.ForeignKey("enterprises.id"), nullable=False),
        sa.Column("account_id", sa.Integer(), sa.ForeignKey("insurer_accounts.id"), nullable=False),
        sa.Column("affected_insurers", sa.String(255), nullable=False, server_default=""),
        sa.Column("affected_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("confirmed_by", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("confirmed_at", sa.DateTime(), nullable=True),
        sa.Column("dismissed_at", sa.DateTime(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )


def downgrade():
    op.drop_table("pending_terminations")
