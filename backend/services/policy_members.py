import json
import secrets
from datetime import datetime, time, timedelta

from fastapi import HTTPException

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import as_business_time, business_now
from ..models import AgentCommission, InsurancePlan, InsuredPerson, Policy, PolicyMember, WorkPosition
from .pricing import plan_price_for_class, pricing_snapshot


def _plan_for_person(session: Session, person: InsuredPerson) -> InsurancePlan | None:
    if not person.position_id:
        return None
    position = session.get(WorkPosition, person.position_id)
    return session.get(InsurancePlan, position.plan_id) if position and position.plan_id else None


def earliest_effective_at(plan: InsurancePlan, operation_time: datetime | None = None) -> datetime:
    """即时生效方案：生效时间就是参保（操作）时间本身；次日生效方案：最早为
    操作日次日零点。"""
    operation = as_business_time(operation_time) if operation_time else business_now()
    if plan.effective_mode == "immediate":
        return operation
    return datetime.combine(operation.date() + timedelta(days=1), time.min)


def earliest_termination_at(plan: InsurancePlan | None, effective_at: datetime | None, operation_time: datetime | None = None) -> datetime:
    """即时生效方案：最早停保时间为生效时间往后推 24 小时（最短保障周期为
    整 24 小时）；次日生效方案（或方案未知）：最早为操作日次日零点（最短保
    障周期为一个完整自然日），与生效时间无关。"""
    if plan is not None and plan.effective_mode == "immediate" and effective_at is not None:
        return as_business_time(effective_at) + timedelta(hours=24)
    operation = as_business_time(operation_time) if operation_time else business_now()
    return datetime.combine(operation.date() + timedelta(days=1), time.min)


def validate_person_policy_dates(
    session: Session,
    person: InsuredPerson,
    effective_at: datetime | None,
    terminated_at: datetime | None,
    operation_time: datetime | None = None,
) -> InsurancePlan | None:
    """Validate explicit business timestamps against plan activation rules."""
    plan = _plan_for_person(session, person)
    if not plan:
        return None
    operation = as_business_time(operation_time) if operation_time else business_now()
    member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person.id).order_by(PolicyMember.id.desc())) if person.id else None
    if effective_at is not None:
        earliest = earliest_effective_at(plan, operation)
        if as_business_time(effective_at) < earliest:
            rule = "参保（操作）时间" if plan.effective_mode == "immediate" else "操作日次日 00:00"
            raise HTTPException(400, f"生效时间不合理：该方案最早可于{rule}生效（{earliest.strftime('%Y-%m-%d %H:%M')}）")
    if terminated_at is not None:
        candidate_effective_for_term = effective_at or (member.effective_at if member else None)
        earliest = earliest_termination_at(plan, candidate_effective_for_term, operation)
        if as_business_time(terminated_at) < earliest:
            rule = "生效时间往后 24 小时" if plan.effective_mode == "immediate" else "操作日次日 00:00"
            raise HTTPException(400, f"停保时间不合理：最早可于{rule}停保（{earliest.strftime('%Y-%m-%d %H:%M')}）")
    candidate_effective = effective_at or (member.effective_at if member else None)
    candidate_termination = terminated_at or (member.terminated_at if member else None)
    if candidate_effective is not None and candidate_termination is not None and as_business_time(candidate_termination) <= as_business_time(candidate_effective):
        raise HTTPException(400, "停保时间必须晚于生效时间")
    return plan


def _find_or_create_policy(session: Session, enterprise_id: int, plan_id: int) -> Policy:
    policy = session.scalar(select(Policy).where(Policy.enterprise_id == enterprise_id, Policy.plan_id == plan_id).order_by(Policy.id.asc()))
    if policy: return policy
    operation = business_now()
    policy_no = f"POL-{operation.strftime('%Y%m%d')}-{secrets.token_hex(3).upper()}"
    policy = Policy(policy_no=policy_no, enterprise_id=enterprise_id, plan_id=plan_id, status="active", start_date=operation.strftime("%Y-%m-%d"))
    session.add(policy); session.flush()
    return policy


def activate_person_policy(session: Session, person: InsuredPerson, effective_at: datetime | None = None) -> PolicyMember | None:
    """Call when person.status transitions INTO 'active' from a non-active
    status. Silently no-ops (same permissiveness as today, where the
    status endpoint has no state-machine validation at all) if the person
    has no position, or the position has no plan_id yet — both are normal,
    reachable states in the current system (review_position doesn't
    require plan_id; re-uploading a video resets an approved position's
    plan_id back to None)."""
    if not person.position_id: return None
    position = session.get(WorkPosition, person.position_id)
    if not position or not position.plan_id: return None
    plan = session.get(InsurancePlan, position.plan_id)
    if not plan: return None
    operation = business_now()
    if effective_at is not None:
        validate_person_policy_dates(session, person, effective_at, None, operation)
    target_effective_at = effective_at or earliest_effective_at(plan, operation)
    latest = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person.id).order_by(PolicyMember.id.desc()))
    if latest and latest.terminated_at is not None and as_business_time(target_effective_at) < as_business_time(latest.terminated_at):
        raise HTTPException(400, f"生效时间不能早于上一保障期间的停保时间（{latest.terminated_at.strftime('%Y-%m-%d %H:%M')}）")
    policy = _find_or_create_policy(session, person.enterprise_id, plan.id)
    relation = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == person.enterprise_id, AgentCommission.plan_id == plan.id, AgentCommission.status == "active").order_by(AgentCommission.id.desc()))
    snapshot = pricing_snapshot(plan, relation, plan_price_for_class(session, plan, person.occupation_class))
    member = PolicyMember(policy_id=policy.id, person_id=person.id, rate_snapshot_json=json.dumps(snapshot, ensure_ascii=False), effective_at=target_effective_at, status="active")
    session.add(member)
    person.policy_id = policy.id
    return member


def terminate_person_policy(
    session: Session,
    person: InsuredPerson,
    terminated_at: datetime | None = None,
    *,
    enforce_timing: bool = True,
) -> PolicyMember | None:
    """Call when person.status transitions OUT of 'active'. Closes (never
    deletes/reuses) the person's currently-open coverage period; no-ops if
    none is found (e.g. activate_person_policy previously skipped them).
    Re-activating this person later always creates a brand-new PolicyMember
    row — this is what produces "两个保障期间" for stop-then-re-enroll
    instead of silently overwriting history (SYSTEM-DESIGN-V4.md 16.2).

    ``enforce_timing=False`` is reserved for an administrator-confirmed
    involuntary stop caused by premium-account shortfall.  That operation is
    deliberately immediate and must not use the voluntary next-day/minimum-
    period validation.  The default remains strict for every existing caller.
    """
    member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person.id, PolicyMember.status == "active", PolicyMember.terminated_at.is_(None)).order_by(PolicyMember.id.desc()))
    if member:
        operation = business_now()
        plan = _plan_for_person(session, person)
        if terminated_at is not None:
            if enforce_timing:
                validate_person_policy_dates(session, person, None, terminated_at, operation)
                target_terminated_at = terminated_at
            else:
                target_terminated_at = as_business_time(terminated_at)
                if as_business_time(target_terminated_at) < as_business_time(member.effective_at):
                    target_terminated_at = as_business_time(member.effective_at)
        else:
            # 即时生效方案默认停保时间为生效时间往后 24 小时；次日生效方案为
            # 操作日次日零点，如与生效时间冲突则顺延到生效当天的次日零点，
            # 保证最短保障周期为一个完整自然日。
            target_terminated_at = earliest_termination_at(plan, member.effective_at, operation)
            if as_business_time(target_terminated_at) <= as_business_time(member.effective_at):
                target_terminated_at = datetime.combine(as_business_time(member.effective_at).date() + timedelta(days=1), time.min)
        member.terminated_at = target_terminated_at
        member.status = "terminated"
    person.policy_id = None
    return member


def effective_person_status(person: InsuredPerson, terminated_at: datetime | None) -> str:
    """A person whose stored status is already 'stopped' can still be
    genuinely covered right now: 临时日结 (temporary daily) coverage sets
    terminated_at to effective_at + 24h and flips status to 'stopped'
    immediately at creation time, since there's no scheduler to flip it
    later at the actual expiry moment. Without this, someone who just
    enrolled shows as already-stopped for the next 24 hours. Returns the
    status that should actually be shown/used, without touching the
    stored value (billing already reads PolicyMember.terminated_at
    directly, not person.status, so this is purely a display/reporting
    correction)."""
    if person.status == "stopped" and terminated_at is not None and as_business_time(terminated_at) > business_now():
        return "active"
    return person.status


def correct_person_policy_dates(session: Session, person: InsuredPerson, effective_at: datetime | None, terminated_at: datetime | None) -> PolicyMember | None:
    """Apply explicitly entered business dates without changing created_at.

    The employee's created_at remains the system operation/import time. The
    effective and termination timestamps belong to the latest coverage row.
    """
    operation = business_now()
    validate_person_policy_dates(session, person, effective_at, terminated_at, operation)
    member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person.id).order_by(PolicyMember.id.desc()))
    if member is None:
        if effective_at is None and terminated_at is None:
            return None
        # terminated_at alone (no explicit effective_at) on a brand-new person
        # is the "临时日结" one-shot flow: activate now with the plan's
        # default earliest effective time, then immediately apply the given
        # termination — not a no-op (see feedback item 10).
        member = activate_person_policy(session, person, effective_at)
        if member is None:
            return None
    if effective_at is not None:
        member.effective_at = effective_at
    if terminated_at is not None:
        member.terminated_at = terminated_at
    member.status = "terminated" if member.terminated_at is not None else "active"
    person.policy_id = None if member.status == "terminated" else member.policy_id
    return member
