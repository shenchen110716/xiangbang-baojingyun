"""add historical employer scopes

Revision ID: d5a4c12f7b91
Revises: f7e2d9b1a4c8
Create Date: 2026-07-16
"""

from alembic import op
import sqlalchemy as sa


revision = "d5a4c12f7b91"
down_revision = "f7e2d9b1a4c8"
branch_labels = None
depends_on = None


def upgrade() -> None:
    context = op.get_context()
    if context.dialect.name == "sqlite" and context.as_sql:
        # SQLite batch mode requires live table reflection.  Offline SQL is a
        # diagnostic artifact only, so emit the supported additive statement;
        # online SQLite and production PostgreSQL still get the full check.
        op.add_column("users", sa.Column("enterprise_role", sa.String(30), nullable=True))
    else:
        with op.batch_alter_table("users") as batch_op:
            batch_op.add_column(sa.Column("enterprise_role", sa.String(30), nullable=True))
            batch_op.create_check_constraint(
                "ck_users_enterprise_role",
                "enterprise_role IS NULL OR enterprise_role IN ('owner', 'project_manager')",
            )
    op.execute(
        "UPDATE users SET enterprise_role = CASE WHEN is_owner THEN 'owner' "
        "ELSE 'project_manager' END WHERE role = 'enterprise'"
    )

    op.create_table(
        "user_employer_scopes",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column(
            "enterprise_id", sa.Integer(), sa.ForeignKey("enterprises.id"), nullable=False
        ),
        sa.Column(
            "actual_employer_id",
            sa.Integer(),
            sa.ForeignKey("actual_employers.id"),
            nullable=False,
        ),
        sa.Column("responsibility_type", sa.String(20), nullable=False),
        sa.Column("granted_by", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("assigned_at", sa.DateTime(), nullable=False),
        sa.Column("revoked_at", sa.DateTime(), nullable=True),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.CheckConstraint(
            "responsibility_type IN ('primary', 'collaborator')",
            name="ck_scope_responsibility_type",
        ),
        sa.CheckConstraint("status IN ('active', 'revoked')", name="ck_scope_status"),
    )
    op.create_index("ix_user_employer_scopes_user_id", "user_employer_scopes", ["user_id"])
    op.create_index(
        "ix_user_employer_scopes_enterprise_id", "user_employer_scopes", ["enterprise_id"]
    )
    op.create_index(
        "ix_user_employer_scopes_actual_employer_id",
        "user_employer_scopes",
        ["actual_employer_id"],
    )
    op.create_index(
        "ix_scope_active_lookup",
        "user_employer_scopes",
        ["user_id", "enterprise_id", "actual_employer_id", "status"],
    )
    live = sa.text("status = 'active' AND revoked_at IS NULL")
    op.create_index(
        "uq_scope_live_assignment",
        "user_employer_scopes",
        ["user_id", "actual_employer_id"],
        unique=True,
        postgresql_where=live,
        sqlite_where=live,
    )
    live_primary = sa.text(
        "responsibility_type = 'primary' AND status = 'active' AND revoked_at IS NULL"
    )
    op.create_index(
        "uq_scope_live_primary",
        "user_employer_scopes",
        ["actual_employer_id"],
        unique=True,
        postgresql_where=live_primary,
        sqlite_where=live_primary,
    )


def downgrade() -> None:
    op.drop_index("uq_scope_live_primary", table_name="user_employer_scopes")
    op.drop_index("uq_scope_live_assignment", table_name="user_employer_scopes")
    op.drop_index("ix_scope_active_lookup", table_name="user_employer_scopes")
    op.drop_index(
        "ix_user_employer_scopes_actual_employer_id", table_name="user_employer_scopes"
    )
    op.drop_index("ix_user_employer_scopes_enterprise_id", table_name="user_employer_scopes")
    op.drop_index("ix_user_employer_scopes_user_id", table_name="user_employer_scopes")
    op.drop_table("user_employer_scopes")
    context = op.get_context()
    if context.dialect.name == "sqlite" and context.as_sql:
        op.drop_column("users", "enterprise_role")
    else:
        with op.batch_alter_table("users") as batch_op:
            batch_op.drop_constraint("ck_users_enterprise_role", type_="check")
            batch_op.drop_column("enterprise_role")
