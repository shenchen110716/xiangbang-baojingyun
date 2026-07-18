"""backfill enterprise owner flag

Revision ID: a1b2c3d4e5f6
Revises: 27951ec2f8ee
Create Date: 2026-07-18 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, Sequence[str], None] = '27951ec2f8ee'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Heal enterprises whose API-created admin never got an owner flag.

    The onboarding endpoint used to create the enterprise's admin with neither
    is_owner nor enterprise_role='owner', so is_enterprise_owner() denied them
    operator management and every employer-scoped read. The code fix stops new
    breakage; this heals rows already written that way.

    Rule mirrors the fixed onboarding logic: for each enterprise that currently
    has NO owner among its role='enterprise' users, the earliest-created such
    user (lowest id) becomes the owner. Enterprises that already have an owner
    are left untouched, which also makes this idempotent on re-apply.

    Boolean literal is sa.true(), never text("1"): PostgreSQL rejects an integer
    on a boolean column (the v4.2 Phase 2 lesson).
    """
    conn = op.get_bind()
    orphaned = conn.execute(sa.text(
        """
        SELECT MIN(id) AS first_id
        FROM users
        WHERE role = 'enterprise' AND enterprise_id IS NOT NULL
        GROUP BY enterprise_id
        HAVING SUM(CASE WHEN is_owner OR enterprise_role = 'owner' THEN 1 ELSE 0 END) = 0
        """
    )).fetchall()
    ids = [row.first_id for row in orphaned if row.first_id is not None]
    if not ids:
        return

    users = sa.table(
        "users",
        sa.column("id", sa.Integer),
        sa.column("is_owner", sa.Boolean),
        sa.column("enterprise_role", sa.String),
    )
    conn.execute(
        users.update()
        .where(users.c.id.in_(ids))
        .values(is_owner=sa.true(), enterprise_role="owner")
    )


def downgrade() -> None:
    """No-op: a data heal cannot be un-applied precisely — once promoted, an
    owner is indistinguishable from one created correctly, and demoting it would
    re-break the enterprise. Reversal, if ever needed, is a manual decision."""
    pass
