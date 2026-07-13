from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.security import current_user
from ..models import Claim, Enterprise, InsurancePlan, InsuredPerson, Policy, User, WorkPosition
from ..services import amount, policy_dict, pricing_snapshot, usage_person_days

router = APIRouter(prefix="/api", tags=["dashboard"])


@router.get("/dashboard")
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    enterprises = session.query(Enterprise).filter(Enterprise.id.in_(enterprise_filter)).all() if enterprise_filter else session.query(Enterprise).all()
    people = session.query(InsuredPerson).filter(InsuredPerson.enterprise_id.in_(enterprise_filter)).all() if enterprise_filter else session.query(InsuredPerson).all()
    active_people=[x for x in people if x.status in {'active','pending'}]
    alerts=[]
    for ent in enterprises:
        today=business_today(); enterprise_active_count=usage_person_days(session,ent.id,today,today)['active_people']
        daily_usage=enterprise_active_count*float(ent.usage_fee_daily or 0.1)
        daily_premium=sum(float(policy_dict(p,session)['premium'] or 0)/(1 if policy_dict(p,session)['billing_mode']=='daily' else 30) for p in session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for account,balance,daily in [('premium',ent.premium_balance,daily_premium),('usage',ent.usage_balance,daily_usage)]:
            days_left=999999 if daily<=0 else balance/daily
            if days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':account,'balance':balance,'daily_burn':daily,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": session.query(Policy).filter(Policy.status == "active", Policy.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Policy).filter(Policy.status == "active").count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": session.query(Claim).filter(Claim.status.not_in(["paid", "closed"]), Claim.enterprise_id.in_(enterprise_filter)).count() if enterprise_filter else session.query(Claim).filter(Claim.status.not_in(["paid", "closed"])).count(), "premium_balance": sum(x.premium_balance for x in enterprises), "usage_balance": sum(x.usage_balance for x in enterprises), "balance_alerts": alerts}

@router.get("/screen/products")
def screen_products(user: User = Depends(current_user), session: Session = Depends(db)):
    result=[]
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policy_query=session.query(Policy).filter(Policy.plan_id==plan.id)
        if user.role=="enterprise" and user.enterprise_id: policy_query=policy_query.filter(Policy.enterprise_id==user.enterprise_id)
        policies=policy_query.all();insured_query=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(WorkPosition.plan_id==plan.id,InsuredPerson.status.in_(['active','pending']))
        if user.role=="enterprise" and user.enterprise_id: insured_query=insured_query.filter(InsuredPerson.enterprise_id==user.enterprise_id)
        people=insured_query.all();enterprise_ids={x.enterprise_id for x in people}|{x.enterprise_id for x in policies};premium_total=sum(float(policy_dict(x,session)['premium'] or 0) for x in policies)
        result.append({"plan_id":plan.id,"insurer":plan.insurer,"product":plan.name,"insured_count":len(people),"enterprise_count":len(enterprise_ids),"premium_total":amount(premium_total),"policy_count":len(policies),**pricing_snapshot(plan)})
    return result
