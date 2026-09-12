"""add position enroll submissions (scan-to-enroll queue) + work_positions payment flags

Revision ID: 427ea6bd360e
Revises: f1a2b3c4d5e6
Create Date: 2026-09-12 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '427ea6bd360e'
down_revision: Union[str, Sequence[str], None] = 'f1a2b3c4d5e6'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.add_column('work_positions', sa.Column('enable_personal_pay', sa.Boolean(), nullable=False, server_default=sa.false()))
    op.add_column('work_positions', sa.Column('enable_employer_pay', sa.Boolean(), nullable=False, server_default=sa.false()))
    op.add_column('work_positions', sa.Column('enroll_token_version', sa.Integer(), nullable=False, server_default=sa.text('0')))

    op.create_table(
        'position_enroll_submissions',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('position_id', sa.Integer(), sa.ForeignKey('work_positions.id'), nullable=False),
        sa.Column('payment_mode', sa.String(length=20), nullable=False),
        sa.Column('name', sa.String(length=80), nullable=False, server_default=''),
        sa.Column('id_number_cipher', sa.Text(), nullable=False, server_default=''),
        sa.Column('phone', sa.String(length=30), nullable=False, server_default=''),
        sa.Column('payment_status', sa.String(length=20), nullable=False, server_default='not_required'),
        sa.Column('order_no', sa.String(length=60), nullable=False, server_default=''),
        sa.Column('review_status', sa.String(length=20), nullable=False, server_default='pending'),
        sa.Column('review_note', sa.Text(), nullable=False, server_default=''),
        sa.Column('reviewed_by', sa.Integer(), sa.ForeignKey('users.id'), nullable=True),
        sa.Column('reviewed_at', sa.DateTime(), nullable=True),
        sa.Column('insured_person_id', sa.Integer(), sa.ForeignKey('insured_people.id'), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False, server_default=sa.func.now()),
    )
    op.create_index(
        'ix_position_enroll_submissions_position_id',
        'position_enroll_submissions', ['position_id'],
    )
    op.create_index(
        'ix_position_enroll_submissions_review_status',
        'position_enroll_submissions', ['review_status'],
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_position_enroll_submissions_review_status', table_name='position_enroll_submissions')
    op.drop_index('ix_position_enroll_submissions_position_id', table_name='position_enroll_submissions')
    op.drop_table('position_enroll_submissions')
    op.drop_column('work_positions', 'enroll_token_version')
    op.drop_column('work_positions', 'enable_employer_pay')
    op.drop_column('work_positions', 'enable_personal_pay')
