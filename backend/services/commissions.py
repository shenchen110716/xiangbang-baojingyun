from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AgentCommission, Enterprise, InsurancePlan, InsuredPerson, User, WorkPosition
from .pricing import pricing_snapshot
from .serialization import amount, serialize


def commission_dict(item:AgentCommission,session:Session) -> dict:
    agent=session.get(User,item.agent_id);enterprise=session.get(Enterprise,item.enterprise_id);plan=session.get(InsurancePlan,item.plan_id)
    return {**serialize(item),'mode':'price' if item.mode in {'price','markup'} else 'rebate','agent_name':agent.name if agent else '', 'enterprise_name':enterprise.name if enterprise else '', 'plan_name':plan.name if plan else '', 'insurer':plan.insurer if plan else '', **(pricing_snapshot(plan,item) if plan else {})}

def agent_commission_rows(session:Session, agent_id:int) -> list[dict]:
    rows=[]
    agent=session.get(User,agent_id)
    for rel in session.scalars(select(AgentCommission).where(AgentCommission.agent_id==agent_id).order_by(AgentCommission.id.desc())):
        plan=session.get(InsurancePlan,rel.plan_id); enterprise=session.get(Enterprise,rel.enterprise_id)
        if not plan or not enterprise: continue
        insured_count=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(InsuredPerson.enterprise_id==rel.enterprise_id,WorkPosition.plan_id==rel.plan_id,InsuredPerson.status!='stopped').count()
        unit=pricing_snapshot(plan,rel)
        rows.append({**serialize(rel),'mode':unit['commission_mode'],'agent_name':agent.name if agent else '','enterprise_name':enterprise.name,'plan_name':plan.name,'insurer':plan.insurer,'insured_count':insured_count,'agent_commission_unit':unit['agent_commission_amount'],'agent_commission_total':amount(unit['agent_commission_amount']*insured_count)})
    return rows

def agent_commission_summary(session:Session, agent_id:int) -> dict:
    rows=agent_commission_rows(session,agent_id)
    active=[r for r in rows if r['status']=='active']
    return {'enterprise_count':len({r['enterprise_id'] for r in active}),'product_count':len(active),'insured_count':sum(r['insured_count'] for r in active),'total_commission':amount(sum(r['agent_commission_total'] for r in active))}
