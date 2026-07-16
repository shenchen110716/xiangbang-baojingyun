from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..models import AgentCommission, Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember
from .accruals import billable_date_range, period_amount
from .pricing import plan_price_for_class, pricing_snapshot
from .serialization import amount, serialize


def policy_dict(policy:Policy,session:Session,scope_person_ids:set[int]|None=None) -> dict:
    enterprise=session.get(Enterprise,policy.enterprise_id);plan=session.get(InsurancePlan,policy.plan_id);relation=session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id==policy.enterprise_id,AgentCommission.plan_id==policy.plan_id,AgentCommission.status=='active').order_by(AgentCommission.id.desc()))
    # 当前自然月的结算区间：即使某人在本月中途停保，只要在本月有过在保天数，
    # 也要按比例计入本次结算金额，不能因为已停保就整段丢弃（反馈条目 8）。
    today=business_today();period_start=date(today.year,today.month,1)
    members=list(session.scalars(select(PolicyMember).where(PolicyMember.policy_id==policy.id)))
    if scope_person_ids is not None: members=[member for member in members if member.person_id in scope_person_ids]
    billed_people:list[tuple[InsuredPerson,float]]=[]
    billed_person_ids=set()
    for member in members:
        billable=billable_date_range(member,period_start,today)
        if billable is None: continue
        start,end=billable
        person=session.get(InsuredPerson,member.person_id)
        if not person: continue
        ratio=period_amount(1.0,plan.billing_mode if plan else 'monthly',start,end)
        billed_people.append((person,ratio))
        billed_person_ids.add(person.id)
    snapshots_ratio=[]
    if plan:
        for person,ratio in billed_people:
            snapshots_ratio.append((pricing_snapshot(plan,relation,plan_price_for_class(session,plan,person.occupation_class)),ratio))
        if not snapshots_ratio: snapshots_ratio=[(pricing_snapshot(plan,relation),1.0)]
    total=lambda key:amount(sum(float(row.get(key,0))*ratio for row,ratio in snapshots_ratio))
    calculated=total('sale_price') if billed_people else (0.0 if scope_person_ids is not None else float(policy.premium or 0))
    unit=snapshots_ratio[0][0] if snapshots_ratio else {}
    return {**serialize(policy),'premium_original':amount(policy.premium),'premium':amount(calculated),'calculated_premium':amount(calculated),'insured_count':len(billed_person_ids),'enterprise_name':enterprise.name if enterprise else '', 'insurer':plan.insurer if plan else '', 'plan_name':plan.name if plan else '', 'billing_mode':plan.billing_mode if plan else 'monthly','effective_mode':plan.effective_mode if plan else 'next_day',**unit,'insurance_base_total':total('insurance_base_price') if billed_people else unit.get('insurance_base_price',0),'policy_floor_total':total('policy_floor_price') if billed_people else unit.get('policy_floor_price',0),'minimum_sale_total':total('minimum_sale_price') if billed_people else unit.get('minimum_sale_price',0),'sale_total':total('sale_price') if billed_people else unit.get('sale_price',0),'total_commission_total':total('total_commission_amount') if billed_people else unit.get('total_commission_amount',0),'agent_commission_total':total('agent_commission_amount') if billed_people else unit.get('agent_commission_amount',0)}
