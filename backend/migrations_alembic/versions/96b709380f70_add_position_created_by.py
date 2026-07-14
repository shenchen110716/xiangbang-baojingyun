"""add created_by to work_positions

Revision ID: 96b709380f70
Revises: 47e93df1d843
Create Date: 2026-07-14 12:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '96b709380f70'
down_revision: Union[str, Sequence[str], None] = '47e93df1d843'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('work_positions', sa.Column('created_by', sa.Integer(), nullable=True))
    op.create_foreign_key('fk_work_positions_created_by_users', 'work_positions', 'users', ['created_by'], ['id'])


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_constraint('fk_work_positions_created_by_users', 'work_positions', type_='foreignkey')
    op.drop_column('work_positions', 'created_by')
