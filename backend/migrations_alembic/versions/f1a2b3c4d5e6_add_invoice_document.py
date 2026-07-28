"""add document_url/document_name to invoices

Revision ID: f1a2b3c4d5e6
Revises: d81f6c2a3e97
Create Date: 2026-07-29 10:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'f1a2b3c4d5e6'
down_revision: Union[str, Sequence[str], None] = 'd81f6c2a3e97'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('invoices', sa.Column('document_url', sa.Text(), nullable=False, server_default=''))
    op.add_column('invoices', sa.Column('document_name', sa.String(length=200), nullable=False, server_default=''))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('invoices', 'document_name')
    op.drop_column('invoices', 'document_url')
