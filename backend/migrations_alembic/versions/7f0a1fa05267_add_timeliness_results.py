"""add timeliness results

Revision ID: 7f0a1fa05267
Revises: c40dab695a66
Create Date: 2026-07-17 13:34:30.306383

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '7f0a1fa05267'
down_revision: Union[str, Sequence[str], None] = 'c40dab695a66'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Operation snapshots, versioned timeliness results, and the recalc outbox.

    No backfill: results are derived, and inventing them for history without
    real employment facts would fabricate metrics (§16, §20.6). Phase 3's
    recalculation fills these tables from facts that actually exist.
    """
    op.create_table(
        "participation_operations",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=True, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True, index=True),
        sa.Column("operation_type", sa.String(20), nullable=False),
        sa.Column("submitted_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("batch_id", sa.Integer, nullable=True),
        sa.Column("plan_id", sa.Integer, sa.ForeignKey("insurance_plans.id"), nullable=True),
        # 规则在操作发生的那一刻冻结；日后改产品不得改写历史判定（§8）。
        sa.Column("rule_snapshot_json", sa.Text, nullable=False, server_default=""),
        sa.Column("submitted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expected_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("insurer_confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("system_sent_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("operation_type IN ('enrollment','termination')", name="ck_op_type"),
    )

    op.create_table(
        "employment_timeliness_results",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False, index=True),
        sa.Column("employment_fact_revision_no", sa.Integer, nullable=False),
        sa.Column("operation_type", sa.String(20), nullable=False),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=False, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("responsible_user_id", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("primary_manager_user_id", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("actual_business_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expected_coverage_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("actual_coverage_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("timeliness_status", sa.String(20), nullable=False),
        sa.Column("delay_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("early_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("coverage_gap_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("excess_premium", sa.Float, nullable=False, server_default="0"),
        sa.Column("early_premium", sa.Float, nullable=False, server_default="0"),
        sa.Column("feedback_status", sa.String(20), nullable=False, server_default=""),
        sa.Column("feedback_deadline_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("responsibility_reason", sa.String(40), nullable=False, server_default="normal"),
        sa.Column("responsibility_evidence_json", sa.Text, nullable=False, server_default=""),
        sa.Column("product_rule_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("calculation_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("calculated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="current"),
        sa.CheckConstraint(
            "timeliness_status IN ('timely','early','late','missing','premature',"
            "'pending','unmatched','conflict')", name="ck_result_status"),
        sa.CheckConstraint(
            "responsibility_reason IN ('source_feedback_late','operator_processing_late',"
            "'system_processing_late','insurer_confirmation_late','unassigned_responsibility','normal')",
            name="ck_result_reason"),
    )
    # §12 重算不得重复生成多个当前结果。Enforced here rather than in the service
    # so a concurrent or buggy recalc cannot publish two current verdicts and
    # leave reports silently picking one.
    op.create_index("ux_result_current", "employment_timeliness_results",
                    ["employment_fact_id", "employment_fact_revision_no", "operation_type",
                     "product_rule_version", "calculation_version"],
                    unique=True,
                    sqlite_where=sa.text("status = 'current'"),
                    postgresql_where=sa.text("status = 'current'"))

    op.create_table(
        "timeliness_outbox",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False),
        sa.Column("reason", sa.String(40), nullable=False, server_default=""),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("last_error", sa.Text, nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("status IN ('pending','processing','done','failed')", name="ck_outbox_status"),
    )
    # 同一事实只允许一条在途任务，否则重复入队会重复计算。
    op.create_index("ux_outbox_live", "timeliness_outbox", ["employment_fact_id"], unique=True,
                    sqlite_where=sa.text("status IN ('pending','processing')"),
                    postgresql_where=sa.text("status IN ('pending','processing')"))


def downgrade() -> None:
    op.drop_index("ux_outbox_live", table_name="timeliness_outbox")
    op.drop_table("timeliness_outbox")
    op.drop_index("ux_result_current", table_name="employment_timeliness_results")
    op.drop_table("employment_timeliness_results")
    op.drop_table("participation_operations")
