from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Claim, Enterprise, InsurancePlan, InsuredPerson, PendingTermination, Policy, PolicyMember, User, WorkPosition
from ..services import allowed_employer_ids, amount, effective_person_status, policy_dict, premium_account_view, premium_accounts_for_enterprise, pricing_snapshot, scan_premium_shortfalls, strip_internal_pricing, usage_account_view, usage_person_days

router = APIRouter(prefix="/api", tags=["dashboard"])


# Both endpoints aggregate enterprise sales, participation and operating data
# across every enterprise when the caller is not enterprise-scoped. Salespeople
# get their own figures from /agents/me instead (SYSTEM-DESIGN-V4.2 5.1).
@router.get("/dashboard", dependencies=[Depends(require_role("admin", "enterprise", detail="业务员请在业务员门户查看本人数据"))])
def dashboard(user: User = Depends(current_user), session: Session = Depends(db)):
    enterprise_filter = [user.enterprise_id] if user.role == "enterprise" and user.enterprise_id else None
    employer_filter = allowed_employer_ids(session,user) if user.role=='enterprise' else None
    project_scoped = employer_filter is not None
    if user.role == "admin":
        scan_premium_shortfalls(session)
    enterprises = session.query(Enterprise).filter(Enterprise.id.in_(enterprise_filter)).all() if enterprise_filter else session.query(Enterprise).all()
    people_query=session.query(InsuredPerson)
    if enterprise_filter: people_query=people_query.filter(InsuredPerson.enterprise_id.in_(enterprise_filter))
    if employer_filter is not None: people_query=people_query.join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(WorkPosition.actual_employer_id.in_(employer_filter))
    people=people_query.all()
    def _status(x):
        if x.status!='stopped': return x.status
        member=session.scalar(select(PolicyMember).where(PolicyMember.person_id==x.id).order_by(PolicyMember.id.desc()))
        return effective_person_status(x,member.terminated_at if member else None)
    active_people=[x for x in people if _status(x) in {'active','pending'}]

    alerts=[]
    premium_agg: dict[int, dict] = {}
    usage_recharged_total = usage_consumed_total = usage_available_total = 0.0
    for ent in enterprises:
        if project_scoped: continue
        uview=usage_account_view(session,ent)
        daily_usage=uview['active_people']*uview['daily_rate']
        usage_recharged_total+=uview['recharged']; usage_consumed_total+=uview['consumed']; usage_available_total+=uview['available']
        active_policies=list(session.scalars(select(Policy).where(Policy.enterprise_id==ent.id,Policy.status=='active')))
        for row in premium_account_view(session, ent):
            insurer_set = set(row["insurers"])
            daily_premium = 0.0
            for p in active_policies:
                plan = session.get(InsurancePlan, p.plan_id)
                if not plan or plan.insurer not in insurer_set: continue
                billing = policy_dict(p, session)
                daily_premium += float(billing['premium'] or 0) / (1 if billing['billing_mode'] == 'daily' else 30)
            days_left = 999999 if daily_premium <= 0 else row["available"] / daily_premium
            if row["account_id"] not in premium_agg:
                premium_agg[row["account_id"]] = {"account_id": row["account_id"], "label": row["label"], "insurers": row["insurers"], "balance": 0.0, "recharged": 0.0, "consumed": 0.0, "available": 0.0}
            agg = premium_agg[row["account_id"]]
            agg["balance"] += row["available"]; agg["recharged"] += row["recharged"]; agg["consumed"] += row["consumed"]; agg["available"] += row["available"]
            if days_left <= int(ent.alert_days or 3):
                alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'premium','account_id':row["account_id"],'label':row["label"],'balance':row["available"],'daily_burn':daily_premium,'days_left':round(days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if days_left<=1 else 'warning'})
        usage_days_left=999999 if daily_usage<=0 else uview['available']/daily_usage
        if usage_days_left <= int(ent.alert_days or 3): alerts.append({'enterprise_id':ent.id,'enterprise_name':ent.name,'account':'usage','balance':uview['available'],'daily_burn':daily_usage,'days_left':round(usage_days_left,1),'alert_days':ent.alert_days or 3,'level':'critical' if usage_days_left<=1 else 'warning'})

    policy_query=session.query(Policy).filter(Policy.status=='active')
    claim_query=session.query(Claim).filter(Claim.status.not_in(['paid','closed']))
    if enterprise_filter:
        policy_query=policy_query.filter(Policy.enterprise_id.in_(enterprise_filter))
        claim_query=claim_query.filter(Claim.enterprise_id.in_(enterprise_filter))
    if employer_filter is not None:
        scoped_people=select(InsuredPerson.id).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.actual_employer_id.in_(employer_filter))
        policy_query=policy_query.join(PolicyMember,Policy.id==PolicyMember.policy_id).filter(PolicyMember.person_id.in_(scoped_people)).distinct()
        claim_query=claim_query.filter(Claim.person_id.in_(scoped_people))
    return {"portal": "enterprise" if user.role == "enterprise" else "admin", "enterprises": len(enterprises), "people": len(people), "active_people":len(active_people), "active_policies": policy_query.count(), "pending_enterprises": session.query(Enterprise).filter(Enterprise.status == "pending").count() if not enterprise_filter else 0, "pending_people": len([x for x in people if x.status == "pending"]), "claims_open": claim_query.count(), "premium_accounts": list(premium_agg.values()), "usage_balance": 0 if project_scoped else amount(usage_available_total), "usage_recharged": 0 if project_scoped else amount(usage_recharged_total), "usage_consumed": 0 if project_scoped else amount(usage_consumed_total), "usage_available": 0 if project_scoped else amount(usage_available_total), "balance_alerts": alerts, "pending_terminations_count": session.query(PendingTermination).filter(PendingTermination.status == "pending").count() if user.role == "admin" else 0}

@router.get("/screen/products", dependencies=[Depends(require_role("admin", "enterprise", detail="业务员请在业务员门户查看本人数据"))])
def screen_products(user: User = Depends(current_user), session: Session = Depends(db)):
    result=[]
    employer_filter=allowed_employer_ids(session,user) if user.role=='enterprise' else None
    for plan in session.scalars(select(InsurancePlan).order_by(InsurancePlan.id.desc())):
        policy_query=session.query(Policy).filter(Policy.plan_id==plan.id)
        if user.role=="enterprise" and user.enterprise_id: policy_query=policy_query.filter(Policy.enterprise_id==user.enterprise_id)
        policies=policy_query.all();insured_query=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(WorkPosition.plan_id==plan.id,InsuredPerson.status.in_(['active','pending']))
        if user.role=="enterprise" and user.enterprise_id: insured_query=insured_query.filter(InsuredPerson.enterprise_id==user.enterprise_id)
        if employer_filter is not None:
            insured_query=insured_query.filter(WorkPosition.actual_employer_id.in_(employer_filter))
            scoped_person_ids=select(InsuredPerson.id).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(InsuredPerson.enterprise_id==user.enterprise_id,WorkPosition.plan_id==plan.id,WorkPosition.actual_employer_id.in_(employer_filter))
            policy_query=policy_query.join(PolicyMember,Policy.id==PolicyMember.policy_id).filter(PolicyMember.person_id.in_(scoped_person_ids)).distinct()
            policies=policy_query.all()
        people=insured_query.all();person_ids={x.id for x in people};enterprise_ids={x.enterprise_id for x in people}|{x.enterprise_id for x in policies};premium_total=sum(float(policy_dict(x,session,person_ids if employer_filter is not None else None)['premium'] or 0) for x in policies)
        result.append(strip_internal_pricing({"plan_id":plan.id,"insurer":plan.insurer,"product":plan.name,"insured_count":len(people),"enterprise_count":len(enterprise_ids),"premium_total":amount(premium_total),"policy_count":len(policies),**pricing_snapshot(plan)},user))
    return result
