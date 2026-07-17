"""add employment facts

Revision ID: c40dab695a66
Revises: d5a4c12f7b91
Create Date: 2026-07-17 09:08:18.483156

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'c40dab695a66'
down_revision: Union[str, Sequence[str], None] = 'd5a4c12f7b91'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# Every status that means "this file has already been confirmed" (§6.1).
_CONFIRMED_FILE_PREDICATE = (
    "status IN ('confirmed','imported_pending_calculation','completed') "
    "AND source_file_hash != ''"
)


def upgrade() -> None:
    """Create the employment fact base.

    No backfill: 存量数据不得自动伪造真实入离职时间 (§16). Existing
    InsuredPerson rows carry participation dates, not real employment dates,
    and inventing facts from them would silently poison every timeliness
    figure Phase 3 computes.
    """
    op.create_table(
        "employment_feedback_batches",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=True),
        sa.Column("source_type", sa.String(20), nullable=False),
        sa.Column("source_filename", sa.String(255), nullable=False, server_default=""),
        sa.Column("source_file_hash", sa.String(64), nullable=False, server_default=""),
        # 原始上传文件必须私有、加密并设置保留期限（§6.4）。The file is stored
        # encrypted outside the web root; only its path lives here. confirm
        # re-derives the report from it rather than trusting the client.
        sa.Column("source_file_path", sa.String(255), nullable=False, server_default=""),
        sa.Column("reported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("imported_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("imported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("total_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("valid_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("invalid_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("status", sa.String(32), nullable=False, server_default="uploaded"),
        sa.Column("preview_version", sa.Integer, nullable=False, server_default="0"),
        sa.Column("confirm_token_digest", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "source_type IN ('manual_import','api','system_sync')", name="ck_batch_source_type"),
        sa.CheckConstraint(
            "status IN ('uploaded','previewed','confirmed','imported_pending_calculation',"
            "'completed','rejected','failed')", name="ck_batch_status"),
    )
    # 同一企业、来源、文件哈希不得重复确认（§6.1）。
    # The predicate must cover every post-confirm status, not just 'confirmed':
    # confirm_import() leaves the batch at 'imported_pending_calculation' (and
    # Phase 3 moves it to 'completed'), so a predicate of status='confirmed'
    # alone would match no row once the transaction settles and would never
    # actually block a re-confirm. 'failed'/'rejected' stay out so a genuinely
    # failed import can be retried with the same file.
    op.create_index("ux_batch_confirmed_file", "employment_feedback_batches",
                    ["enterprise_id", "source_type", "source_file_hash"], unique=True,
                    sqlite_where=sa.text(_CONFIRMED_FILE_PREDICATE),
                    postgresql_where=sa.text(_CONFIRMED_FILE_PREDICATE))

    op.create_table(
        "employment_facts",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=False, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True, index=True),
        sa.Column("external_employee_no", sa.String(64), nullable=False, server_default=""),
        sa.Column("external_employment_id", sa.String(64), nullable=False, server_default=""),
        sa.Column("id_number_hash", sa.String(64), nullable=False, server_default=""),
        sa.Column("id_number_cipher", sa.Text, nullable=False, server_default=""),
        sa.Column("person_name", sa.String(64), nullable=False, server_default=""),
        sa.Column("actual_hire_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actual_leave_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("feedback_reported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("batch_id", sa.Integer, sa.ForeignKey("employment_feedback_batches.id"), nullable=True),
        sa.Column("source_event_id", sa.String(64), nullable=True),
        sa.Column("revision_no", sa.Integer, nullable=False, server_default="1"),
        sa.Column("previous_version_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=True),
        sa.Column("status", sa.String(20), nullable=False, server_default="active"),
        sa.Column("created_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("actual_leave_at IS NULL OR actual_leave_at > actual_hire_at",
                           name="ck_fact_leave_after_hire"),
        sa.CheckConstraint(
            "status IN ('active','superseded','pending_match','conflict','voided')",
            name="ck_fact_status"),
    )
    # source_event_id 在数据源身份范围内唯一，保证外部推送幂等（§6.2）
    op.create_index("ux_fact_source_event", "employment_facts",
                    ["enterprise_id", "source_event_id"], unique=True,
                    sqlite_where=sa.text("source_event_id IS NOT NULL"),
                    postgresql_where=sa.text("source_event_id IS NOT NULL"))
    op.create_index("ix_fact_scope_window", "employment_facts",
                    ["enterprise_id", "actual_employer_id", "actual_hire_at"])

    op.create_table(
        "employment_fact_matches",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False, index=True),
        sa.Column("match_status", sa.String(20), nullable=False),
        sa.Column("match_method", sa.String(32), nullable=False),
        sa.Column("candidate_person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("matched_person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("confidence", sa.Float, nullable=False, server_default="0"),
        sa.Column("reason", sa.String(255), nullable=False, server_default=""),
        sa.Column("confirmed_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("match_status IN ('matched','pending','ambiguous','rejected')",
                           name="ck_match_status"),
        sa.CheckConstraint(
            "match_method IN ('external_employment_id','identity_hire','employee_no','manual')",
            name="ck_match_method"),
    )


    # §7.3 外部用工事件接口的认证身份。Secrets are stored hashed, never in
    # plaintext; allowed_employer_ids pins the scope server-side so a request
    # body can never widen it.
    op.create_table(
        "integration_api_keys",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("name", sa.String(64), nullable=False, server_default=""),
        sa.Column("key_id", sa.String(32), nullable=False, unique=True),
        sa.Column("secret_cipher", sa.Text, nullable=False),
        sa.Column("allowed_employer_ids", sa.Text, nullable=False, server_default=""),
        # sa.true(), not text("1"): PostgreSQL rejects an integer default on a
        # boolean column ("column active is of type boolean but default
        # expression is of type integer"), while SQLite accepts either. Let the
        # dialect render its own literal.
        sa.Column("active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "integration_nonces",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("key_id", sa.String(32), nullable=False),
        sa.Column("nonce", sa.String(64), nullable=False),
        sa.Column("seen_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ux_nonce_per_key", "integration_nonces", ["key_id", "nonce"], unique=True)

    # §7.3 要求外部接入调用有完整审计，但机器调用没有 users 行可归属，而
    # audit_logs.user_id 原为 NOT NULL。放宽为可空（只放松约束，向后兼容；
    # audit_logs 只追加不改写），机器调用以 user_id IS NULL + detail 中的
    # key_id 标识。
    context = op.get_context()
    if context.dialect.name == "sqlite" and context.as_sql:
        # Offline SQLite SQL is a diagnostic artifact only; batch mode needs a
        # live table to reflect.
        pass
    else:
        with op.batch_alter_table("audit_logs") as batch_op:
            batch_op.alter_column("user_id", existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    context = op.get_context()
    if not (context.dialect.name == "sqlite" and context.as_sql):
        with op.batch_alter_table("audit_logs") as batch_op:
            batch_op.alter_column("user_id", existing_type=sa.Integer(), nullable=False)
    op.drop_index("ux_nonce_per_key", table_name="integration_nonces")
    op.drop_table("integration_nonces")
    op.drop_table("integration_api_keys")
    op.drop_table("employment_fact_matches")
    op.drop_index("ix_fact_scope_window", table_name="employment_facts")
    op.drop_index("ux_fact_source_event", table_name="employment_facts")
    op.drop_table("employment_facts")
    op.drop_index("ux_batch_confirmed_file", table_name="employment_feedback_batches")
    op.drop_table("employment_feedback_batches")
