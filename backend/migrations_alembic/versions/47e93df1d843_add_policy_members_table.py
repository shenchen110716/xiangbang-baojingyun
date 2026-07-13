"""add policy_members table

Revision ID: 47e93df1d843
Revises: e8b0f74f84c8
Create Date: 2026-07-13 12:44:01.695249

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '47e93df1d843'
down_revision: Union[str, Sequence[str], None] = 'e8b0f74f84c8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# NOTE: hand-trimmed of unrelated SQLite ALTER-TABLE drift noise picked up
# by autogenerate — see 51879226f1d6's comment for why that happens and why
# it's deliberately left alone rather than "fixed" as a side effect here.
def upgrade() -> None:
    """Upgrade schema."""
    op.create_table('policy_members',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('policy_id', sa.Integer(), nullable=False),
    sa.Column('person_id', sa.Integer(), nullable=False),
    sa.Column('rate_snapshot_json', sa.Text(), nullable=False),
    sa.Column('effective_at', sa.DateTime(), nullable=False),
    sa.Column('terminated_at', sa.DateTime(), nullable=True),
    sa.Column('endorsement_no', sa.String(length=80), nullable=False),
    sa.Column('status', sa.String(length=20), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['person_id'], ['insured_people.id'], ),
    sa.ForeignKeyConstraint(['policy_id'], ['policies.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index(op.f('ix_policy_members_person_id'), 'policy_members', ['person_id'], unique=False)


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index(op.f('ix_policy_members_person_id'), table_name='policy_members')
    op.drop_table('policy_members')
