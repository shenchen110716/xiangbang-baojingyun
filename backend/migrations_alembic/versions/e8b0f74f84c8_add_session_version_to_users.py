"""add session_version to users

Revision ID: e8b0f74f84c8
Revises: 51879226f1d6
Create Date: 2026-07-13 12:06:01.241563

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e8b0f74f84c8'
down_revision: Union[str, Sequence[str], None] = '51879226f1d6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# NOTE: hand-trimmed to just the session_version column, same as
# 51879226f1d6 — see the comment there for why the autogenerate diff
# against the real local data.db also contains unrelated SQLite
# ALTER-TABLE drift that this migration intentionally does not touch.
def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('users', sa.Column('session_version', sa.Integer(), nullable=False, server_default='1'))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('users', 'session_version')
