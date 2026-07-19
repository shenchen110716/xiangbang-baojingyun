import json
from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..models import AgentCommission, Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember, User, WorkPosition
from .accruals import billable_date_range, period_amount
from .pricing import plan_price_for_class, pricing_snapshot
from .serialization import amount, serialize


def commission_accrual(session: Session, item: AgentCommission, as_of: date | None = None) -> dict:
    """Accrue total rebate and salesperson commission over real coverage days."""
    accrual_time = as_of or business_now()
    cutoff = accrual_time.date() if isinstance(accrual_time, datetime) else accrual_time
    plan = session.get(InsurancePlan, item.plan_id)
    if not plan:
        return {"accrued_total_commission": 0, "accrued_agent_commission": 0, "accrued_person_count": 0, "accrual_as_of": cutoff.isoformat()}
    total_commission = 0.0
    agent_commission = 0.0
    person_ids: set[int] = set()
    members = session.scalars(
        select(PolicyMember)
        .join(Policy, Policy.id == PolicyMember.policy_id)
        .where(Policy.enterprise_id == item.enterprise_id, Policy.plan_id == item.plan_id)
        .order_by(PolicyMember.id.asc())
    )
    for member in members:
        billable = billable_date_range(member, member.effective_at.date(), cutoff, accrual_time)
        if billable is None:
            continue
        try:
            snapshot = json.loads(member.rate_snapshot_json or "{}")
        except (TypeError, ValueError, json.JSONDecodeError):
            snapshot = {}
        if "total_commission_amount" not in snapshot or "agent_commission_amount" not in snapshot:
            person = session.get(InsuredPerson, member.person_id)
            snapshot = pricing_snapshot(plan, item, plan_price_for_class(session, plan, person.occupation_class if person else ""))
        start, end = billable
        total_commission += period_amount(float(snapshot.get("total_commission_amount", 0)), plan.billing_mode, start, end)
        agent_commission += period_amount(float(snapshot.get("agent_commission_amount", 0)), plan.billing_mode, start, end)
        person_ids.add(member.person_id)
    return {
        "accrued_total_commission": amount(total_commission),
        "accrued_agent_commission": amount(agent_commission),
        "accrued_person_count": len(person_ids),
        "accrual_as_of": cutoff.isoformat(),
    }


def commission_dict(item:AgentCommission,session:Session) -> dict:
    agent=session.get(User,item.agent_id);enterprise=session.get(Enterprise,item.enterprise_id);plan=session.get(InsurancePlan,item.plan_id)
    unit=pricing_snapshot(plan,item) if plan else {}
    return {**serialize(item),'mode':'price' if item.mode in {'price','markup'} else 'rebate','agent_name':agent.name if agent else '', 'enterprise_name':enterprise.name if enterprise else '', 'plan_name':plan.name if plan else '', 'insurer':plan.insurer if plan else '', **unit, **commission_accrual(session,item)}

def agent_commission_rows(session:Session, agent_id:int) -> list[dict]:
    rows=[]
    agent=session.get(User,agent_id)
    for rel in session.scalars(select(AgentCommission).where(AgentCommission.agent_id==agent_id).order_by(AgentCommission.id.desc())):
        plan=session.get(InsurancePlan,rel.plan_id); enterprise=session.get(Enterprise,rel.enterprise_id)
        if not plan or not enterprise: continue
        insured_count=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(InsuredPerson.enterprise_id==rel.enterprise_id,WorkPosition.plan_id==rel.plan_id,InsuredPerson.status!='stopped').count()
        unit=pricing_snapshot(plan,rel);accrual=commission_accrual(session,rel)
        rows.append({**serialize(rel),'mode':unit['commission_mode'],'agent_name':agent.name if agent else '','enterprise_name':enterprise.name,'plan_name':plan.name,'insurer':plan.insurer,'insured_count':insured_count,'minimum_sale_price':unit['minimum_sale_price'],'sale_price':unit['sale_price'],'agent_commission_unit':unit['agent_commission_amount'],'agent_commission_amount':unit['agent_commission_amount'],'agent_commission_total':accrual['accrued_agent_commission'],**accrual})
    return rows

def agent_commission_summary(session:Session, agent_id:int) -> dict:
    rows=agent_commission_rows(session,agent_id)
    active=[r for r in rows if r['status']=='active']
    return {'enterprise_count':len({r['enterprise_id'] for r in active}),'product_count':len(active),'insured_count':sum(r['insured_count'] for r in active),'total_commission':amount(sum(r['agent_commission_total'] for r in active))}
