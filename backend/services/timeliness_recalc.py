"""Recalculate timeliness from facts and coverage (v4.2 §8, §12).

The impure half of the engine: it reads facts and PolicyMember coverage, calls
the pure judge, and writes versioned results. Results are superseded rather than
mutated, so a changed verdict stays explainable.

System-level reads: `active_facts` (Phase 2) requires a `user` because it
applies Phase 1's employer scope, but recalculation is a background process with
no user. `system_facts()` below is the system path — it drops the *scope*
filter, which a background job legitimately has no basis for, but keeps §20.6's
status exclusion, which is a correctness rule and not a permissions one.
Recomputing an unmatched or conflicting fact into a published rate would be a
data bug, not a privilege escalation.

Timezone seam: PolicyMember and other Phase 1-era columns are naive DateTime
holding UTC, while employment facts are timezone-aware. Everything is compared
in one frame here (see `_utc`), otherwise the ladders raise mid-computation.
"""
import json
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..models import (
    EmploymentFact,
    EmploymentFeedbackBatch,
    EmploymentTimelinessResult,
    InsurancePlan,
    InsuredPerson,
    ParticipationOperation,
    PolicyMember,
    TimelinessOutbox,
    WorkPosition,
)
from .employment_facts import FACT_EXCLUDED_STATUSES
from .timeliness_engine import (
    Coverage,
    EnrollmentInput,
    TerminationInput,
    judge_enrollment,
    judge_feedback,
    judge_termination,
)
from .timeliness_responsibility import attribute
from .timeliness_rules import RULE_VERSION, rule_snapshot

CALCULATION_VERSION = 1
MAX_OUTBOX_ATTEMPTS = 5


def _utc(value: Optional[datetime]) -> Optional[datetime]:
    """One frame for every comparison: aware UTC."""
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def system_facts(session: Session, *, enterprise_id: Optional[int] = None) -> list[EmploymentFact]:
    """Authoritative facts for a background job: no user scope, but §20.6's
    status exclusion still applies."""
    stmt = select(EmploymentFact).where(
        EmploymentFact.status.not_in(tuple(FACT_EXCLUDED_STATUSES)))
    if enterprise_id is not None:
        stmt = stmt.where(EmploymentFact.enterprise_id == enterprise_id)
    return list(session.scalars(stmt.order_by(EmploymentFact.id)))


def _plan_for(session: Session, fact: EmploymentFact) -> Optional[InsurancePlan]:
    if not fact.person_id:
        return None
    person = session.get(InsuredPerson, fact.person_id)
    if not person or not person.position_id:
        return None
    position = session.get(WorkPosition, person.position_id)
    if not position or not position.plan_id:
        return None
    return session.get(InsurancePlan, position.plan_id)


def _operation_for(session: Session, fact: EmploymentFact,
                   operation_type: str) -> Optional[ParticipationOperation]:
    if not fact.person_id:
        return None
    return session.scalar(
        select(ParticipationOperation)
        .where(ParticipationOperation.person_id == fact.person_id,
               ParticipationOperation.operation_type == operation_type)
        .order_by(ParticipationOperation.submitted_at.desc()))


def _rule_for(session: Session, fact: EmploymentFact,
              operation: Optional[ParticipationOperation]) -> dict:
    """The rule frozen at operation time wins; only fall back to today's product
    when no operation exists, since there is nothing frozen to honour (§8)."""
    if operation is not None and operation.rule_snapshot_json:
        try:
            return json.loads(operation.rule_snapshot_json)
        except json.JSONDecodeError:
            pass
    plan = _plan_for(session, fact)
    if plan is not None:
        return rule_snapshot(plan)
    return {
        "billing_mode": "monthly", "effective_mode": "next_day",
        "leave_is_last_working_day": True, "min_coverage_seconds": 0,
        "business_timezone": "Australia/Melbourne",
        "feedback_grace_seconds": 86400, "rule_version": RULE_VERSION,
    }


def _coverages(session: Session, fact: EmploymentFact) -> list[Coverage]:
    """PolicyMember 是保障期唯一权威（§8）；绝不从 InsuredPerson.status 或
    policy_id 推断——usage-coverage-authority-hotfix 已经证明那种推断是错的。"""
    if not fact.person_id:
        return []
    rows = session.scalars(
        select(PolicyMember).where(PolicyMember.person_id == fact.person_id))
    return [Coverage(_utc(r.effective_at), _utc(r.terminated_at)) for r in rows]


def _supersede_and_insert(session: Session, *, fact: EmploymentFact, operation_type: str,
                          verdict, reason: str, responsible_user_id, evidence: dict,
                          rule: dict, feedback) -> EmploymentTimelinessResult:
    key = dict(
        employment_fact_id=fact.id,
        employment_fact_revision_no=fact.revision_no,
        operation_type=operation_type,
        product_rule_version=int(rule.get("rule_version") or RULE_VERSION),
        calculation_version=CALCULATION_VERSION,
    )
    # 让位再插入：ux_result_current 只允许一条 current（§12）。
    session.execute(
        update(EmploymentTimelinessResult)
        .where(EmploymentTimelinessResult.status == "current",
               *[getattr(EmploymentTimelinessResult, k) == v for k, v in key.items()])
        .values(status="superseded"))
    session.flush()

    row = EmploymentTimelinessResult(
        **key,
        enterprise_id=fact.enterprise_id,
        actual_employer_id=fact.actual_employer_id,
        person_id=fact.person_id,
        responsible_user_id=responsible_user_id,
        primary_manager_user_id=responsible_user_id,
        actual_business_at=(fact.actual_hire_at if operation_type == "enrollment"
                            else fact.actual_leave_at),
        expected_coverage_at=verdict.expected_at,
        actual_coverage_at=verdict.actual_at,
        timeliness_status=verdict.status,
        delay_seconds=verdict.delay_seconds,
        early_seconds=verdict.early_seconds,
        coverage_gap_seconds=verdict.coverage_gap_seconds,
        feedback_status=feedback.status if feedback else "",
        feedback_deadline_at=feedback.expected_at if feedback else None,
        responsibility_reason=reason,
        responsibility_evidence_json=json.dumps(evidence, ensure_ascii=False),
        calculated_at=business_now(),
        status="current",
    )
    session.add(row)
    session.flush()
    return row


def recalculate(session: Session, *, fact_id: int, now: Optional[datetime] = None) -> list:
    """Recompute both operations for one fact. Idempotent by §12's key."""
    fact = session.get(EmploymentFact, fact_id)
    if not fact:
        return []
    moment = _utc(now) or _utc(business_now())
    results = []

    for operation_type in ("enrollment", "termination"):
        operation = _operation_for(session, fact, operation_type)
        rule = _rule_for(session, fact, operation)
        coverages = _coverages(session, fact)

        if fact.status in FACT_EXCLUDED_STATUSES:
            # 未匹配/冲突的事实仍留痕，但不进正式口径（§20.6）。
            from .timeliness_engine import Verdict
            verdict = Verdict("unmatched" if fact.status == "pending_match" else "conflict")
            reason, uid, evidence = "normal", None, {}
            feedback = None
        elif operation_type == "enrollment":
            verdict = judge_enrollment(EnrollmentInput(
                hire_at=_utc(fact.actual_hire_at), now=moment,
                coverages=coverages, rule=rule))
            feedback = judge_feedback(event_at=_utc(fact.actual_hire_at),
                                      reported_at=_utc(fact.feedback_reported_at),
                                      rule=rule, event_type="enrollment")
            reason, uid, evidence = attribute(session, fact=fact, verdict=verdict,
                                              operation=operation)
        else:
            terminated = None
            live = [c for c in coverages if c.terminated_at is not None]
            if live:
                terminated = max(c.terminated_at for c in live)
            verdict = judge_termination(TerminationInput(
                leave_at=_utc(fact.actual_leave_at), now=moment,
                terminated_at=terminated, rule=rule))
            feedback = (judge_feedback(event_at=_utc(fact.actual_leave_at),
                                       reported_at=_utc(fact.feedback_reported_at),
                                       rule=rule, event_type="termination")
                        if fact.actual_leave_at else None)
            reason, uid, evidence = attribute(session, fact=fact, verdict=verdict,
                                              operation=operation)

        results.append(_supersede_and_insert(
            session, fact=fact, operation_type=operation_type, verdict=verdict,
            reason=reason, responsible_user_id=uid, evidence=evidence,
            rule=rule, feedback=feedback))

    _advance_batch(session, fact)
    return results


def _advance_batch(session: Session, fact: EmploymentFact) -> None:
    """Phase 2 leaves a batch at imported_pending_calculation; it becomes
    completed once every one of its facts has a current result."""
    if not fact.batch_id:
        return
    batch = session.get(EmploymentFeedbackBatch, fact.batch_id)
    if not batch or batch.status != "imported_pending_calculation":
        return
    fact_ids = list(session.scalars(
        select(EmploymentFact.id).where(EmploymentFact.batch_id == batch.id)))
    done = set(session.scalars(
        select(EmploymentTimelinessResult.employment_fact_id).where(
            EmploymentTimelinessResult.employment_fact_id.in_(fact_ids),
            EmploymentTimelinessResult.status == "current")))
    if fact_ids and all(fid in done for fid in fact_ids):
        batch.status = "completed"
        batch.updated_at = business_now()
        session.flush()


def enqueue(session: Session, *, fact_id: int, reason: str = "") -> Optional[TimelinessOutbox]:
    """One live entry per fact — a double enqueue would double-compute."""
    existing = session.scalar(select(TimelinessOutbox).where(
        TimelinessOutbox.employment_fact_id == fact_id,
        TimelinessOutbox.status.in_(("pending", "processing"))))
    if existing:
        return existing
    row = TimelinessOutbox(employment_fact_id=fact_id, reason=reason[:40],
                           status="pending", created_at=business_now())
    session.add(row)
    session.flush()
    return row


def process_outbox(session: Session, *, limit: int = 100) -> dict:
    """Claim and run pending recalcs.

    Claiming uses the conditional-update pattern already proved by the
    pending_terminations confirm path: only the request whose UPDATE matched a
    row proceeds, so two workers cannot compute the same fact.
    """
    processed = failed = 0
    pending = list(session.scalars(
        select(TimelinessOutbox)
        .where(TimelinessOutbox.status == "pending")
        .order_by(TimelinessOutbox.id).limit(limit)))

    for row in pending:
        claimed = session.execute(
            update(TimelinessOutbox)
            .where(TimelinessOutbox.id == row.id, TimelinessOutbox.status == "pending")
            .values(status="processing")).rowcount
        if claimed != 1:
            continue
        try:
            recalculate(session, fact_id=row.employment_fact_id)
            row.status = "done"
            row.processed_at = business_now()
            row.last_error = ""
            processed += 1
        except Exception as exc:  # noqa: BLE001 - the error is recorded, not swallowed
            row.attempts += 1
            row.last_error = str(exc)[:500]
            # Bounded retries: an endlessly retried row would hide a real defect
            # behind a queue that never drains.
            row.status = "failed" if row.attempts >= MAX_OUTBOX_ATTEMPTS else "pending"
            failed += 1
        session.flush()
    return {"processed": processed, "failed": failed}


def record_operation(session: Session, *, user, person: InsuredPerson,
                     operation_type: str, batch_id: Optional[int] = None) -> Optional[ParticipationOperation]:
    """Freeze who did what, under which rule, at the five participation writes.

    Written once and never updated: 即使人员或负责人之后调岗，历史操作归属也不能
    改变（§8）. Best-effort — a snapshot failure must not block the participation
    write itself, which is the user's actual intent; the recalc will fall back to
    the product's current rule and the gap shows up in data quality.
    """
    if person is None or not person.enterprise_id:
        return None
    position = session.get(WorkPosition, person.position_id) if person.position_id else None
    plan = session.get(InsurancePlan, position.plan_id) if position and position.plan_id else None
    row = ParticipationOperation(
        enterprise_id=person.enterprise_id,
        actual_employer_id=position.actual_employer_id if position else None,
        person_id=person.id,
        operation_type=operation_type,
        submitted_by=getattr(user, "id", None),
        batch_id=batch_id,
        plan_id=plan.id if plan else None,
        rule_snapshot_json=json.dumps(rule_snapshot(plan), ensure_ascii=False) if plan else "",
        submitted_at=business_now(),
    )
    session.add(row)
    session.flush()
    return row
