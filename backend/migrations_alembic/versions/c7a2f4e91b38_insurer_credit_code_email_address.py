"""add credit_code/email/address to insurers (+ pending_* variants)

Revision ID: c7a2f4e91b38
Revises: b4f19a7d2e63
Create Date: 2026-07-24

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c7a2f4e91b38'
down_revision: Union[str, Sequence[str], None] = 'b4f19a7d2e63'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('insurers', sa.Column('credit_code', sa.String(length=40), nullable=False, server_default=''))
    op.add_column('insurers', sa.Column('email', sa.String(length=160), nullable=False, server_default=''))
    op.add_column('insurers', sa.Column('address', sa.String(length=200), nullable=False, server_default=''))
    op.add_column('insurers', sa.Column('pending_credit_code', sa.String(length=40), nullable=True))
    op.add_column('insurers', sa.Column('pending_email', sa.String(length=160), nullable=True))
    op.add_column('insurers', sa.Column('pending_address', sa.String(length=200), nullable=True))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('insurers', 'pending_address')
    op.drop_column('insurers', 'pending_email')
    op.drop_column('insurers', 'pending_credit_code')
    op.drop_column('insurers', 'address')
    op.drop_column('insurers', 'email')
    op.drop_column('insurers', 'credit_code')
