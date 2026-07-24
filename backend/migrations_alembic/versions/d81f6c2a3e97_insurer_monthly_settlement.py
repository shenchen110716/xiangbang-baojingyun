"""add insurer_monthly_settlements table

Revision ID: d81f6c2a3e97
Revises: c7a2f4e91b38
Create Date: 2026-07-24

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd81f6c2a3e97'
down_revision: Union[str, Sequence[str], None] = 'c7a2f4e91b38'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # 唯一约束和建表放在同一个 create_table 调用里（用 sa.UniqueConstraint 传给
    # table_args 风格的位置参数），不要拆成建表后再 op.create_unique_constraint——
    # SQLite 不支持事后 ALTER 加约束，拆开写在 SQLite 本地开发库上会直接报
    # NotImplementedError（PostgreSQL 生产库能过，但这个仓库的本地/测试链路
    # 是 SQLite，两边都要能跑）。
    op.create_table(
        'insurer_monthly_settlements',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=False),
        sa.Column('month', sa.String(length=7), nullable=False),
        sa.Column('settled_at', sa.DateTime(), nullable=False),
        sa.Column('settled_by', sa.Integer(), sa.ForeignKey('users.id'), nullable=True),
        sa.Column('note', sa.String(length=200), nullable=False, server_default=''),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.UniqueConstraint('insurer_id', 'month', name='uq_insurer_monthly_settlement'),
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_table('insurer_monthly_settlements')
