"""add document_url/document_name to policies

Revision ID: e59219cc15ef
Revises: 96b709380f70
Create Date: 2026-07-14 20:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'e59219cc15ef'
down_revision: Union[str, Sequence[str], None] = '96b709380f70'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('policies', sa.Column('document_url', sa.Text(), nullable=False, server_default=''))
    op.add_column('policies', sa.Column('document_name', sa.String(length=200), nullable=False, server_default=''))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('policies', 'document_name')
    op.drop_column('policies', 'document_url')
