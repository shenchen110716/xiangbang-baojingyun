"""Authoritative real-employment fact reads and corrections (v4.2 §6.2, §20.6).

Phase 3 computes every timeliness figure from `active_facts`, so the exclusion
rule here is the one that keeps unmatched or disputed rows out of published
metrics. Corrections append a new revision and mark the old one superseded —
authoritative time values are never rewritten in place.
"""
from datetime import datetime
from typing import Iterable, Optional

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..core.id_number import id_decrypt, mask_id_number
from ..models import EmploymentFact, User
from .employer_scopes import allowed_employer_ids, assert_employer_access

# 无真实用工事实、未匹配或冲突记录不进入正式指标（§20.6）。
FACT_EXCLUDED_STATUSES: frozenset[str] = frozenset(
    {"superseded", "pending_match", "conflict", "voided"}
)


def active_facts(
    session: Session,
    user: User,
    *,
    employer_ids: Optional[Iterable[int]] = None,
    since: Optional[datetime] = None,
    until: Optional[datetime] = None,
) -> list[EmploymentFact]:
    """Scope-filtered authoritative facts. Phase 3 consumes exactly this."""
    stmt = select(EmploymentFact).where(EmploymentFact.status == "active")

    allowed = allowed_employer_ids(session, user)
    if allowed is not None:
        # An empty scope set is deliberate no-access, not "unfiltered".
        if not allowed:
            return []
        stmt = stmt.where(EmploymentFact.actual_employer_id.in_(allowed))
    if user.role == "enterprise":
        stmt = stmt.where(EmploymentFact.enterprise_id == user.enterprise_id)
    if employer_ids is not None:
        stmt = stmt.where(EmploymentFact.actual_employer_id.in_(list(employer_ids)))
    if since is not None:
        stmt = stmt.where(EmploymentFact.actual_hire_at >= since)
    if until is not None:
        stmt = stmt.where(EmploymentFact.actual_hire_at <= until)
    return list(session.scalars(stmt.order_by(EmploymentFact.id)))


def correct_fact(
    session: Session,
    user: User,
    fact_id: int,
    *,
    actual_hire_at: Optional[datetime] = None,
    actual_leave_at: Optional[datetime] = None,
    reason: str,
) -> EmploymentFact:
    """纠错创建新版本并将旧版本标记 superseded，不得覆盖旧值（§6.2）。

    Returns the new version.
    """
    if not (reason or "").strip():
        raise HTTPException(400, "修正必须填写原因")
    old = session.get(EmploymentFact, fact_id)
    if not old:
        raise HTTPException(404, "用工事实不存在")
    if old.status != "active":
        raise HTTPException(409, "只能修正当前有效版本")
    assert_employer_access(session, user, old.actual_employer_id)

    hire = actual_hire_at or old.actual_hire_at
    leave = actual_leave_at if actual_leave_at is not None else old.actual_leave_at
    if leave is not None and leave <= hire:
        raise HTTPException(400, "真实离职时间必须晚于真实入职时间")

    new = EmploymentFact(
        enterprise_id=old.enterprise_id,
        actual_employer_id=old.actual_employer_id,
        person_id=old.person_id,
        external_employee_no=old.external_employee_no,
        external_employment_id=old.external_employment_id,
        id_number_hash=old.id_number_hash,
        id_number_cipher=old.id_number_cipher,
        person_name=old.person_name,
        actual_hire_at=hire,
        actual_leave_at=leave,
        feedback_reported_at=old.feedback_reported_at,
        batch_id=old.batch_id,
        # The idempotency key belongs to the original external event; reusing it
        # would collide with ux_fact_source_event and make the correction fail.
        source_event_id=None,
        revision_no=old.revision_no + 1,
        previous_version_id=old.id,
        status="active",
        created_by=user.id,
        created_at=business_now(),
    )
    old.status = "superseded"  # 只改状态，绝不覆盖旧时间值
    session.add(new)
    session.flush()

    # Queue both versions: the old one must have its verdict retired, the new
    # one needs a verdict of its own. Kept here rather than in the router
    # because it is a correctness invariant of correcting a fact — a future
    # caller that forgot it would leave reports showing a judgement the user
    # already corrected. Imported lazily: timeliness_recalc imports this module.
    from .timeliness_recalc import enqueue

    enqueue(session, fact_id=old.id, reason="fact_superseded")
    enqueue(session, fact_id=new.id, reason="fact_corrected")
    return new


def serialize_fact(fact: EmploymentFact) -> dict:
    """The only shape allowed out of the API: identity is always masked (§6.4)."""
    return {
        "id": fact.id,
        "enterprise_id": fact.enterprise_id,
        "actual_employer_id": fact.actual_employer_id,
        "person_id": fact.person_id,
        "person_name": fact.person_name,
        "id_number": mask_id_number(id_decrypt(fact.id_number_cipher)) if fact.id_number_cipher else "",
        "external_employee_no": fact.external_employee_no,
        "external_employment_id": fact.external_employment_id,
        "actual_hire_at": fact.actual_hire_at,
        "actual_leave_at": fact.actual_leave_at,
        "feedback_reported_at": fact.feedback_reported_at,
        "revision_no": fact.revision_no,
        "previous_version_id": fact.previous_version_id,
        "status": fact.status,
        "batch_id": fact.batch_id,
        "created_at": fact.created_at,
    }
