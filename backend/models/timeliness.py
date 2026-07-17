"""Timeliness operation snapshots, results and the recalc outbox (v4.2 §8, §12).

``ParticipationOperation`` freezes who submitted what, when, and under which
product rule. It is written once and never updated: 即使人员或负责人之后调岗，
历史操作归属也不能改变 (§8).

``EmploymentTimelinessResult`` is versioned rather than mutable. A recalc
supersedes the old row instead of editing it, so a changed verdict stays
explainable. The partial unique index is what actually enforces §12's "one
current result per idempotency key" — the service alone could not survive a
concurrent recalc.

``TimelinessOutbox`` decouples "a fact changed" from "recompute it", so an
import transaction never blocks on calculation.
"""
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


def _now() -> datetime:
    return datetime.now(timezone.utc)


class ParticipationOperation(Base):
    __tablename__ = "participation_operations"
    __table_args__ = (
        CheckConstraint("operation_type IN ('enrollment','termination')", name="ck_op_type"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("actual_employers.id"), nullable=True, index=True)
    person_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("insured_people.id"), nullable=True, index=True)
    operation_type: Mapped[str] = mapped_column(String(20))
    submitted_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    batch_id: Mapped[Optional[int]] = mapped_column(Integer, nullable=True)
    plan_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurance_plans.id"), nullable=True)
    # 操作发生时的规则快照；日后改产品不得改写历史判定（§8）。
    rule_snapshot_json: Mapped[str] = mapped_column(Text, default="")
    submitted_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    expected_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    insurer_confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    system_sent_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class EmploymentTimelinessResult(Base):
    __tablename__ = "employment_timeliness_results"
    __table_args__ = (
        CheckConstraint(
            "timeliness_status IN ('timely','early','late','missing','premature',"
            "'pending','unmatched','conflict')", name="ck_result_status"),
        CheckConstraint(
            "responsibility_reason IN ('source_feedback_late','operator_processing_late',"
            "'system_processing_late','insurer_confirmation_late','unassigned_responsibility','normal')",
            name="ck_result_reason"),
        # §12 重算不得重复生成多个当前结果。
        Index(
            "ux_result_current",
            "employment_fact_id", "employment_fact_revision_no", "operation_type",
            "product_rule_version", "calculation_version",
            unique=True,
            sqlite_where=text("status = 'current'"),
            postgresql_where=text("status = 'current'"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    employment_fact_id: Mapped[int] = mapped_column(ForeignKey("employment_facts.id"), index=True)
    employment_fact_revision_no: Mapped[int] = mapped_column(Integer)
    operation_type: Mapped[str] = mapped_column(String(20))
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[int] = mapped_column(ForeignKey("actual_employers.id"), index=True)
    person_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insured_people.id"), nullable=True)
    responsible_user_id: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    primary_manager_user_id: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    actual_business_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    expected_coverage_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    actual_coverage_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    timeliness_status: Mapped[str] = mapped_column(String(20))
    delay_seconds: Mapped[int] = mapped_column(Integer, default=0)
    early_seconds: Mapped[int] = mapped_column(Integer, default=0)
    coverage_gap_seconds: Mapped[int] = mapped_column(Integer, default=0)
    excess_premium: Mapped[float] = mapped_column(Float, default=0)
    early_premium: Mapped[float] = mapped_column(Float, default=0)
    feedback_status: Mapped[str] = mapped_column(String(20), default="")
    feedback_deadline_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    responsibility_reason: Mapped[str] = mapped_column(String(40), default="normal")
    responsibility_evidence_json: Mapped[str] = mapped_column(Text, default="")
    product_rule_version: Mapped[int] = mapped_column(Integer, default=1)
    calculation_version: Mapped[int] = mapped_column(Integer, default=1)
    calculated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    status: Mapped[str] = mapped_column(String(20), default="current")


class TimelinessOutbox(Base):
    __tablename__ = "timeliness_outbox"
    __table_args__ = (
        CheckConstraint("status IN ('pending','processing','done','failed')",
                        name="ck_outbox_status"),
        # 同一事实只允许一条在途任务，否则重复入队会重复计算。
        Index(
            "ux_outbox_live", "employment_fact_id", unique=True,
            sqlite_where=text("status IN ('pending','processing')"),
            postgresql_where=text("status IN ('pending','processing')"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    employment_fact_id: Mapped[int] = mapped_column(ForeignKey("employment_facts.id"))
    reason: Mapped[str] = mapped_column(String(40), default="")
    status: Mapped[str] = mapped_column(String(20), default="pending")
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    last_error: Mapped[str] = mapped_column(Text, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    processed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
