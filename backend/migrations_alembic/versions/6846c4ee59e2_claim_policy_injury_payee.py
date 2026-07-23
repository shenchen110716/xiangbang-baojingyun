"""add policy_id/injury_part/payee_type to claims

Revision ID: 6846c4ee59e2
Revises: b7c8d9e0f1a2
Create Date: 2026-07-24

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '6846c4ee59e2'
down_revision: Union[str, Sequence[str], None] = 'b7c8d9e0f1a2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('claims', sa.Column('policy_id', sa.Integer(), sa.ForeignKey('policies.id'), nullable=True))
    op.add_column('claims', sa.Column('injury_part', sa.String(length=80), nullable=False, server_default=''))
    op.add_column('claims', sa.Column('payee_type', sa.String(length=20), nullable=False, server_default=''))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('claims', 'payee_type')
    op.drop_column('claims', 'injury_part')
    op.drop_column('claims', 'policy_id')
