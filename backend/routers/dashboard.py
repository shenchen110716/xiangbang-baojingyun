from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.security import current_user
from ..models import Claim, Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember, User, WorkPosition
from ..services import amount, effective_person_status, policy_dict, premium_accounts_for_enterprise, pricing_snapshot, strip_internal_pricing, usage_person_days

router = APIRouter(prefix="/api", tags=["dashboard"])


@router.get("/dashboard")
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    enterprises = session.query(Enterprise).filter(Enterprise.id.in_(enterprise_filter)).all() if enterprise_filter else session.query(Enterprise).all()
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id.in_(enterprise_filter)).all() if enterprise_filter else session.query(InsuredPerson).all()
    def _status(x):
        if x.status!='stopped': return x.status
        member=session.scalar(select(PolicyMember).where(PolicyMember.person_id==x.id).order_by(PolicyMember.id.desc()))
        return effective_person_status(x,member.terminated_at if member else None)
    active_people=[x for x in people if _status(x) in {'active','pending'}]

    alerts=[]
    premium_agg: dict[int, dict] = {}
    for ent in enterprises:
        today=business_today(); enterprise_active_count=usage_person_days(session,ent.id,today,today)['active_people']
        daily_usage=enterprise_active_count*float(ent.usage_fee_daily or 0.1)
        active_policies=list(session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for row in premium_accounts_for_enterprise(session, ent.id):
            insurer_set = set(row["insurers"])
            daily_premium = 0.0
            for p in active_policies:
                plan = session.get(InsurancePlan, p.plan_id)
                if not plan or plan.insurer not in insurer_set: continue
                billing = policy_dict(p, session)
                daily_premium += float(billing['premium'] or 0) / (1 if billing['billing_mode'] == 'daily' else 30)
            days_left = 999999 if daily_premium <= 0 else row["balance"] / daily_premium
            if row["account_id"] not in premium_agg:
                premium_agg[row["account_id"]] = {"account_id": row["account_id"], "label": row["label"], "insurers": row["insurers"], "balance": 0.0}
            premium_agg[row["account_id"]]["balance"] += row["balance"]
            if days_left <= int(ent.alert_days or 3):
                alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'premium','account_id':row["account_id"],'label':row["label"],'balance':row["balance"],'daily_burn':daily_premium,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
        usage_days_left=999999 if daily_usage<=0 else ent.usage_balance/daily_usage
        if usage_days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'usage','balance':ent.usage_balance,'daily_burn':daily_usage,'days_left':round(usage_days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if usage_days_left<=1 else 'warning'})

    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_accounts": list(premium_agg.values()), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts}

@router.get("/screen/products")
def screen_products(user: User = Depends(current_user), session: Session = Depends(db)):
    result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policy_query=session.query(Policy).filter(Policy.plan_id==plan.id)
        if user.role=="enterprise" and user.enterprise_id: policy_query=policy_query.filter(Policy.enterprise_id==user.enterprise_id)
        policies=policy_query.all();insured_query=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(WorkPosition.plan_id==plan.id,InsuredPerson.status.in_(['active','pending']))
        if user.role=="enterprise" and user.enterprise_id: insured_query=insured_query.filter(InsuredPerson.enterprise_id==user.enterprise_id)
        people=insured_query.all();enterprise_ids={x.enterprise_id for x in people}|{x.enterprise_id for x in policies};premium_total=sum(float(policy_dict(x,session)['premium'] or 0) for x in policies)
        result.append(strip_internal_pricing({"plan_id":plan.id,"insurer":plan.insurer,"product":plan.name,"insured_count":len(people),"enterprise_count":len(enterprise_ids),"premium_total":amount(premium_total),"policy_count":len(policies),**pricing_snapshot(plan)},user))
    return result
