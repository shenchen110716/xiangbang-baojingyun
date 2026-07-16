"""Real employment facts (v4.2 §6).

Three tables, deliberately separate:

- ``EmploymentFeedbackBatch`` owns the upload/preview/confirm lifecycle.
- ``EmploymentFact`` is the authoritative, append-only fact base. A correction
  never rewrites a row: it inserts a new revision and marks the old one
  ``superseded`` (§6.2), so the history stays auditable.
- ``EmploymentFactMatch`` holds identity-matching workflow rows. Kept out of
  ``EmploymentFact`` so candidate noise can never pollute authoritative facts
  (§6.3).

Identity numbers live here as ciphertext plus a keyed digest; plaintext only
ever exists inside an import transaction (§6.4).
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


# Every status that means "this file has already been confirmed" (§6.1).
# Kept identical to the same constant in the c40dab695a66 migration.
_CONFIRMED_FILE_PREDICATE = (
    "status IN ('confirmed','imported_pending_calculation','completed') "
    "AND source_file_hash != ''"
)


class EmploymentFeedbackBatch(Base):
    __tablename__ = "employment_feedback_batches"
    __table_args__ = (
        CheckConstraint(
            "source_type IN ('manual_import','api','system_sync')",
            name="ck_batch_source_type",
        ),
        CheckConstraint(
            "status IN ('uploaded','previewed','confirmed','imported_pending_calculation',"
            "'completed','rejected','failed')",
            name="ck_batch_status",
        ),
        # 同一企业、来源、文件哈希不得重复确认（§6.1）。Covers every
        # post-confirm status: confirm_import() settles the batch at
        # 'imported_pending_calculation', so a 'confirmed'-only predicate would
        # match no row and block nothing. 'failed'/'rejected' stay out so a
        # failed import can be retried with the same file.
        Index(
            "ux_batch_confirmed_file",
            "enterprise_id", "source_type", "source_file_hash",
            unique=True,
            sqlite_where=text(_CONFIRMED_FILE_PREDICATE),
            postgresql_where=text(_CONFIRMED_FILE_PREDICATE),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("actual_employers.id"), nullable=True)
    source_type: Mapped[str] = mapped_column(String(20))
    source_filename: Mapped[str] = mapped_column(String(255), default="")
    source_file_hash: Mapped[str] = mapped_column(String(64), default="")
    reported_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    imported_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    imported_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    total_rows: Mapped[int] = mapped_column(Integer, default=0)
    valid_rows: Mapped[int] = mapped_column(Integer, default=0)
    invalid_rows: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(32), default="uploaded")
    preview_version: Mapped[int] = mapped_column(Integer, default=0)
    # Only the digest is stored, so a leaked row cannot be used to confirm.
    confirm_token_digest: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)


class EmploymentFact(Base):
    __tablename__ = "employment_facts"
    __table_args__ = (
        CheckConstraint(
            "actual_leave_at IS NULL OR actual_leave_at > actual_hire_at",
            name="ck_fact_leave_after_hire",
        ),
        CheckConstraint(
            "status IN ('active','superseded','pending_match','conflict','voided')",
            name="ck_fact_status",
        ),
        # source_event_id 在数据源身份范围内唯一，保证外部推送幂等（§6.2）
        Index(
            "ux_fact_source_event",
            "enterprise_id", "source_event_id",
            unique=True,
            sqlite_where=text("source_event_id IS NOT NULL"),
            postgresql_where=text("source_event_id IS NOT NULL"),
        ),
        Index("ix_fact_scope_window", "enterprise_id", "actual_employer_id", "actual_hire_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    enterprise_id: Mapped[int] = mapped_column(ForeignKey("enterprises.id"), index=True)
    actual_employer_id: Mapped[int] = mapped_column(ForeignKey("actual_employers.id"), index=True)
    person_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("insured_people.id"), nullable=True, index=True)
    external_employee_no: Mapped[str] = mapped_column(String(64), default="")
    external_employment_id: Mapped[str] = mapped_column(String(64), default="")
    id_number_hash: Mapped[str] = mapped_column(String(64), default="")
    id_number_cipher: Mapped[str] = mapped_column(Text, default="")
    person_name: Mapped[str] = mapped_column(String(64), default="")
    actual_hire_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    actual_leave_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    feedback_reported_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    batch_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("employment_feedback_batches.id"), nullable=True)
    source_event_id: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    revision_no: Mapped[int] = mapped_column(Integer, default=1)
    previous_version_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("employment_facts.id"), nullable=True)
    status: Mapped[str] = mapped_column(String(20), default="active")
    created_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)


class EmploymentFactMatch(Base):
    __tablename__ = "employment_fact_matches"
    __table_args__ = (
        CheckConstraint(
            "match_status IN ('matched','pending','ambiguous','rejected')",
            name="ck_match_status",
        ),
        CheckConstraint(
            "match_method IN ('external_employment_id','identity_hire','employee_no','manual')",
            name="ck_match_method",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    employment_fact_id: Mapped[int] = mapped_column(
        ForeignKey("employment_facts.id"), index=True)
    match_status: Mapped[str] = mapped_column(String(20))
    match_method: Mapped[str] = mapped_column(String(32))
    candidate_person_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("insured_people.id"), nullable=True)
    matched_person_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("insured_people.id"), nullable=True)
    confidence: Mapped[float] = mapped_column(Float, default=0)
    reason: Mapped[str] = mapped_column(String(255), default="")
    confirmed_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
    confirmed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
