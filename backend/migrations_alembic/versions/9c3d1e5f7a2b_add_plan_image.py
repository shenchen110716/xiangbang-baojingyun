"""add insurance_plans image columns (plan brochure image)

Revision ID: 9c3d1e5f7a2b
Revises: 427ea6bd360e
Create Date: 2026-09-14 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '9c3d1e5f7a2b'
down_revision: Union[str, Sequence[str], None] = '427ea6bd360e'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('insurance_plans', sa.Column('image_url', sa.Text(), nullable=False, server_default=''))
    op.add_column('insurance_plans', sa.Column('image_name', sa.String(length=200), nullable=False, server_default=''))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('insurance_plans', 'image_name')
    op.drop_column('insurance_plans', 'image_url')
