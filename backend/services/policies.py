from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AgentCommission, Enterprise, InsurancePlan, InsuredPerson, Policy
from .pricing import plan_price_for_class, pricing_snapshot
from .serialization import amount, serialize


def policy_dict(policy:Policy,session:Session) -> dict:
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id);relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==policy.enterprise_id,AgentCommission.plan_id==policy.plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()))
    people=list(session.scalars(select(InsuredPerson).where(InsuredPerson.policy_id==policy.id)))
    snapshots=[]
    if plan:
        for person in people:
            snapshots.append(pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)))
        if not snapshots: snapshots=[pricing_snapshot(plan,relation)]
    total=lambda key:amount(sum(float(row.get(key,0)) for row in snapshots))
    calculated=total('sale_price') if people else float(policy.premium or 0)
    unit=snapshots[0] if snapshots else {}
    return {**serialize(policy),'premium_original':amount(policy.premium),'premium':amount(calculated),'calculated_premium':amount(calculated),'insured_count':len(people),'enterprise_name':enterprise.name if enterprise else '', 'insurer':plan.insurer if plan else '', 'plan_name':plan.name if plan else '', 'billing_mode':plan.billing_mode if plan else 'monthly','effective_mode':plan.effective_mode if plan else 'next_day',**unit,'insurance_base_total':total('insurance_base_price') if people else unit.get('insurance_base_price',0),'policy_floor_total':total('policy_floor_price') if people else unit.get('policy_floor_price',0),'minimum_sale_total':total('minimum_sale_price') if people else unit.get('minimum_sale_price',0),'sale_total':total('sale_price') if people else unit.get('sale_price',0),'total_commission_total':total('total_commission_amount') if people else unit.get('total_commission_amount',0),'agent_commission_total':total('agent_commission_amount') if people else unit.get('agent_commission_amount',0)}
