"""add recharge accounts

Revision ID: c3e7aebc5c9a
Revises: e59219cc15ef
Create Date: 2026-07-15 15:30:13.095792

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c3e7aebc5c9a'
down_revision: Union[str, Sequence[str], None] = 'e59219cc15ef'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'insurer_accounts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('label', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('bank_name', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('account_no', sa.String(length=60), nullable=False, server_default=''),
        sa.Column('account_holder', sa.String(length=100), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'insurer_account_links',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('insurer', sa.String(length=100), nullable=False),
        sa.Column('account_id', sa.Integer(), nullable=False),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'enterprise_premium_accounts',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('enterprise_id', sa.Integer(), nullable=False),
        sa.Column('account_id', sa.Integer(), nullable=False),
        sa.Column('balance', sa.Float(), nullable=False, server_default='0'),
        sa.ForeignKeyConstraint(['enterprise_id'], ['enterprises.id']),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.create_table(
        'recharge_requests',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('enterprise_id', sa.Integer(), nullable=False),
        sa.Column('account_type', sa.String(length=20), nullable=False),
        sa.Column('insurer', sa.String(length=100), nullable=True),
        sa.Column('account_id', sa.Integer(), nullable=True),
        sa.Column('amount', sa.Float(), nullable=False, server_default='0'),
        sa.Column('receipt_file_url', sa.String(length=255), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('reject_reason', sa.String(length=255), nullable=False, server_default=''),
        sa.Column('created_by', sa.Integer(), nullable=False),
        sa.Column('confirmed_by', sa.Integer(), nullable=True),
        sa.Column('confirmed_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(['enterprise_id'], ['enterprises.id']),
        sa.ForeignKeyConstraint(['account_id'], ['insurer_accounts.id']),
        sa.ForeignKeyConstraint(['created_by'], ['users.id']),
        sa.ForeignKeyConstraint(['confirmed_by'], ['users.id']),
        sa.PrimaryKeyConstraint('id'),
    )
    op.add_column('ledger_entries', sa.Column('account_id', sa.Integer(), nullable=True))
    op.create_foreign_key('fk_ledger_entries_account_id', 'ledger_entries', 'insurer_accounts', ['account_id'], ['id'])


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_constraint('fk_ledger_entries_account_id', 'ledger_entries', type_='foreignkey')
    op.drop_column('ledger_entries', 'account_id')
    op.drop_table('recharge_requests')
    op.drop_table('enterprise_premium_accounts')
    op.drop_table('insurer_account_links')
    op.drop_table('insurer_accounts')
