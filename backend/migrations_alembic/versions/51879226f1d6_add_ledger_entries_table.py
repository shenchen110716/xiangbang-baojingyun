"""add ledger_entries table

Revision ID: 51879226f1d6
Revises: 9536dc0da3f7
Create Date: 2026-07-13 11:57:56.440576

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '51879226f1d6'
down_revision: Union[str, Sequence[str], None] = '9536dc0da3f7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# NOTE: `alembic revision --autogenerate` against the real local data.db also
# picked up a large batch of unrelated NOT NULL/foreign-key tightening and
# two dropped legacy columns (users.total_commission, users.commission_rate)
# — pre-existing drift between SQLite's ALTER-TABLE-based schema history
# (backend/core/migrations.py) and the declarative models, unrelated to this
# change. That's a separate cleanup to do deliberately, not as a side effect
# of adding one table, so this migration was hand-trimmed to only the
# ledger_entries table.
def upgrade() -> None:
    """Upgrade schema."""
    op.create_table('ledger_entries',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('enterprise_id', sa.Integer(), nullable=False),
    sa.Column('account', sa.String(length=20), nullable=False),
    sa.Column('direction', sa.String(length=10), nullable=False),
    sa.Column('amount', sa.Numeric(precision=18, scale=2), nullable=False),
    sa.Column('business_type', sa.String(length=40), nullable=False),
    sa.Column('business_id', sa.String(length=80), nullable=False),
    sa.Column('idempotency_key', sa.String(length=120), nullable=False),
    sa.Column('created_by', sa.Integer(), nullable=True),
    sa.Column('occurred_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['created_by'], ['users.id'], ),
    sa.ForeignKeyConstraint(['enterprise_id'], ['enterprises.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_ledger_entries_idempotency_key'), 'ledger_entries', ['idempotency_key'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_ledger_entries_idempotency_key'), table_name='ledger_entries')
    op.drop_table('ledger_entries')
