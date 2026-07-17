"""Attribute a timeliness verdict to whoever was actually responsible (§11.3).

Two rules shape everything here:

- Responsibility follows the *event's* moment, not today's org chart. Phase 1
  stored employer scopes historically (assigned_at/revoked_at) precisely so this
  lookup is possible.
- 当时没有主要负责人时为 unassigned_responsibility，不得归给当前管理员. Blaming
  today's manager for something that predates them produces a confident, wrong
  number that a person gets judged on. Admitting nobody was assigned is the
  honest answer, and it points at the real gap: the employer had no owner.

One main reason is stored for aggregation; the full time chain lives in
`evidence` so a disputed verdict can be argued from facts rather than re-derived.
"""
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import EmploymentFact, ParticipationOperation, UserEmployerScope
from .timeliness_engine import Verdict

# 责任原因（与 ck_result_reason 约束一致）
NORMAL = "normal"
SOURCE_FEEDBACK_LATE = "source_feedback_late"
OPERATOR_PROCESSING_LATE = "operator_processing_late"
SYSTEM_PROCESSING_LATE = "system_processing_late"
INSURER_CONFIRMATION_LATE = "insurer_confirmation_late"
UNASSIGNED = "unassigned_responsibility"

_BLAMELESS_STATUSES = frozenset({"timely", "early", "pending"})


def _scope_frame(value: Optional[datetime]) -> Optional[datetime]:
    """Put a moment in the same frame as UserEmployerScope's columns.

    Phase 1 and everything older declare their datetimes as naive `DateTime`
    holding UTC, while Phase 2/3 use `DateTime(timezone=True)`. Comparing the
    two raises "can't compare offset-naive and offset-aware datetimes", and in a
    query it would silently compare mismatched representations instead. Rather
    than rewrite every legacy column, convert here and keep the seam visible.
    """
    if value is None or value.tzinfo is None:
        return value
    return value.astimezone(timezone.utc).replace(tzinfo=None)


def primary_manager_at(session: Session, *, actual_employer_id: int,
                       moment: datetime) -> Optional[int]:
    """Who held primary responsibility for this employer at `moment`.

    Reads the historical scope rows, so a revoked manager still owns their era
    and a later appointment never inherits earlier events.
    """
    at = _scope_frame(moment)
    stmt = select(UserEmployerScope).where(
        UserEmployerScope.actual_employer_id == actual_employer_id,
        UserEmployerScope.responsibility_type == "primary",
        UserEmployerScope.assigned_at <= at,
    ).order_by(UserEmployerScope.assigned_at.desc())

    for scope in session.scalars(stmt):
        revoked = _scope_frame(scope.revoked_at)
        if revoked is None or revoked > at:
            return scope.user_id
    return None


def _evidence(fact: EmploymentFact, operation: Optional[ParticipationOperation],
              verdict: Verdict) -> dict:
    def iso(value):
        return value.isoformat() if isinstance(value, datetime) else None

    return {
        "feedback_reported_at": iso(getattr(fact, "feedback_reported_at", None)),
        "submitted_at": iso(getattr(operation, "submitted_at", None)),
        "system_sent_at": iso(getattr(operation, "system_sent_at", None)),
        "insurer_confirmed_at": iso(getattr(operation, "insurer_confirmed_at", None)),
        "expected_coverage_at": iso(verdict.expected_at),
        "actual_coverage_at": iso(verdict.actual_at),
        "delay_seconds": verdict.delay_seconds,
        "batch_id": getattr(operation, "batch_id", None),
    }


def _late_reason(fact: EmploymentFact, operation: ParticipationOperation) -> str:
    """Which link in the chain actually ran late.

    Ordered from the outside in: if the enterprise reported after the operation
    was submitted, the operator could not have acted sooner; if the insurer
    confirmed after we sent, that is not the operator's delay either.
    """
    reported = getattr(fact, "feedback_reported_at", None)
    submitted = getattr(operation, "submitted_at", None)
    sent = getattr(operation, "system_sent_at", None)
    confirmed = getattr(operation, "insurer_confirmed_at", None)

    if reported and submitted and reported > submitted:
        return SOURCE_FEEDBACK_LATE
    if sent and submitted and sent > submitted and confirmed and confirmed > sent:
        return INSURER_CONFIRMATION_LATE
    if sent and submitted and sent > submitted:
        return SYSTEM_PROCESSING_LATE
    return OPERATOR_PROCESSING_LATE


def attribute(session: Session, *, fact: EmploymentFact, verdict: Verdict,
              operation: Optional[ParticipationOperation]) -> tuple[str, Optional[int], dict]:
    """Returns (reason, responsible_user_id, evidence)."""
    evidence = _evidence(fact, operation, verdict)

    if operation is not None and operation.submitted_by:
        # Someone did act: they own the outcome, good or bad. A batch row is
        # owned by whoever confirmed the import, not by the file.
        if verdict.status in _BLAMELESS_STATUSES:
            return NORMAL, operation.submitted_by, evidence
        return _late_reason(fact, operation), operation.submitted_by, evidence

    if verdict.status in _BLAMELESS_STATUSES:
        return NORMAL, None, evidence

    # Nobody acted (missing/premature/late with no operation): fall back to
    # whoever was responsible for the employer when the event happened.
    moment = fact.actual_hire_at if fact.actual_hire_at else None
    manager = (primary_manager_at(session, actual_employer_id=fact.actual_employer_id,
                                  moment=moment)
               if moment is not None else None)
    if manager is None:
        # 不得归给当前管理员（§11.3）。
        return UNASSIGNED, None, evidence
    return OPERATOR_PROCESSING_LATE, manager, evidence
