import json
import secrets
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AgentCommission, InsurancePlan, InsuredPerson, Policy, PolicyMember, WorkPosition
from .pricing import plan_price_for_class, pricing_snapshot


def _find_or_create_policy(session: Session, enterprise_id: int, plan_id: int) -> Policy:
    policy = session.scalar(select(Policy).where(Policy.enterprise_id == enterprise_id, Policy.plan_id == plan_id).order_by(Policy.id.asc()))
    if policy: return policy
    policy_no = f"POL-{datetime.now().strftime('%Y%m%d')}-{secrets.token_hex(3).upper()}"
    policy = Policy(policy_no=policy_no, enterprise_id=enterprise_id, plan_id=plan_id, status="active", start_date=datetime.now().strftime("%Y-%m-%d"))
    session.add(policy); session.flush()
    return policy


def activate_person_policy(session: Session, person: InsuredPerson) -> None:
    """Call when person.status transitions INTO 'active' from a non-active
    status. Silently no-ops (same permissiveness as today, where the
    status endpoint has no state-machine validation at all) if the person
    has no position, or the position has no plan_id yet — both are normal,
    reachable states in the current system (review_position doesn't
    require plan_id; re-uploading a video resets an approved position's
    plan_id back to None)."""
    if not person.position_id: return
    position = session.get(WorkPosition, person.position_id)
    if not position or not position.plan_id: return
    plan = session.get(InsurancePlan, position.plan_id)
    if not plan: return
    policy = _find_or_create_policy(session, person.enterprise_id, plan.id)
    relation = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == person.enterprise_id, AgentCommission.plan_id == plan.id, AgentCommission.status == "active").order_by(AgentCommission.id.desc()))
    snapshot = pricing_snapshot(plan, relation, plan_price_for_class(session, plan, person.occupation_class))
    session.add(PolicyMember(policy_id=policy.id, person_id=person.id, rate_snapshot_json=json.dumps(snapshot, ensure_ascii=False), effective_at=datetime.now(timezone.utc), status="active"))
    person.policy_id = policy.id


def terminate_person_policy(session: Session, person: InsuredPerson) -> None:
    """Call when person.status transitions OUT of 'active'. Closes (never
    deletes/reuses) the person's currently-open coverage period; no-ops if
    none is found (e.g. activate_person_policy previously skipped them).
    Re-activating this person later always creates a brand-new PolicyMember
    row — this is what produces "两个保障期间" for stop-then-re-enroll
    instead of silently overwriting history (SYSTEM-DESIGN-V4.md 16.2)."""
    member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person.id, PolicyMember.status == "active", PolicyMember.terminated_at.is_(None)).order_by(PolicyMember.id.desc()))
    if member:
        member.terminated_at = datetime.now(timezone.utc)
        member.status = "terminated"
    person.policy_id = None
