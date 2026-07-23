"""add insurer entity, backfill from insurer strings, insured-person flag

Revision ID: b4f19a7d2e63
Revises: 6846c4ee59e2
Create Date: 2026-07-24

"""
from datetime import datetime, timezone
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b4f19a7d2e63'
down_revision: Union[str, Sequence[str], None] = '6846c4ee59e2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'insurers',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('contact', sa.String(length=80), nullable=False, server_default=''),
        sa.Column('phone', sa.String(length=30), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('pending_name', sa.String(length=100), nullable=True),
        sa.Column('pending_contact', sa.String(length=80), nullable=True),
        sa.Column('pending_phone', sa.String(length=30), nullable=True),
        sa.Column('pending_submitted_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
    )

    conn = op.get_bind()
    names: set[str] = set()
    for row in conn.execute(sa.text(
        "SELECT DISTINCT insurer FROM insurance_plans WHERE insurer IS NOT NULL AND insurer <> ''"
    )):
        names.add(row[0])
    for row in conn.execute(sa.text(
        "SELECT DISTINCT insurer FROM insurer_account_links WHERE insurer IS NOT NULL AND insurer <> ''"
    )):
        names.add(row[0])

    insurers_table = sa.table(
        'insurers',
        sa.column('name', sa.String),
        sa.column('contact', sa.String),
        sa.column('phone', sa.String),
        sa.column('status', sa.String),
        sa.column('created_at', sa.DateTime),
    )
    now = datetime.now(timezone.utc)
    for name in sorted(names):
        conn.execute(insurers_table.insert().values(
            name=name, contact='', phone='', status='active', created_at=now,
        ))

    op.add_column('insurance_plans', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('insurer_account_links', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('users', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('insured_people', sa.Column('insurer_flag_reason', sa.Text(), nullable=False, server_default=''))
    op.add_column('insured_people', sa.Column('insurer_flagged_at', sa.DateTime(), nullable=True))
    op.add_column('insured_people', sa.Column('insurer_flagged_by', sa.Integer(), sa.ForeignKey('users.id'), nullable=True))

    conn.execute(sa.text(
        "UPDATE insurance_plans SET insurer_id = "
        "(SELECT id FROM insurers WHERE insurers.name = insurance_plans.insurer) "
        "WHERE insurer IS NOT NULL AND insurer <> ''"
    ))
    conn.execute(sa.text(
        "UPDATE insurer_account_links SET insurer_id = "
        "(SELECT id FROM insurers WHERE insurers.name = insurer_account_links.insurer) "
        "WHERE insurer IS NOT NULL AND insurer <> ''"
    ))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('insured_people', 'insurer_flagged_by')
    op.drop_column('insured_people', 'insurer_flagged_at')
    op.drop_column('insured_people', 'insurer_flag_reason')
    op.drop_column('users', 'insurer_id')
    op.drop_column('insurer_account_links', 'insurer_id')
    op.drop_column('insurance_plans', 'insurer_id')
    op.drop_table('insurers')
