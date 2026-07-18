from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user, pwd
from ..models import AgentCommission, Enterprise, InsurancePlan, InsuredPerson, LedgerEntry, Policy, User, WorkPosition
from ..schemas import AgentIn, EnterpriseIn, EnterpriseUpdate, RechargeIn
from ..services import amount, ledger_dict, post_ledger_entry, premium_account_view, premium_accounts_for_enterprise, pricing_snapshot, reconcile_enterprise_ledger, serialize, strip_internal_pricing, usage_account_view

router = APIRouter(prefix="/api", tags=["enterprises"])


@router.get("/enterprises")
def enterprises(q: str = "", status_filter: Optional[str] = Query(None, alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(Enterprise).order_by(Enterprise.id.desc())
    if user.role == "enterprise" and user.enterprise_id: stmt = stmt.where(Enterprise.id == user.enterprise_id)
    if q: stmt = stmt.where(or_(Enterprise.name.contains(q), Enterprise.contact.contains(q)))
    if status_filter: stmt = stmt.where(Enterprise.status == status_filter)
    result=[]
    for x in session.scalars(stmt):
        linked = session.scalar(select(AgentCommission).where(AgentCommission.enterprise_id == x.id).order_by(AgentCommission.id.asc())) if not x.agent_id else None
        agent_id = x.agent_id or (linked.agent_id if linked else None)
        item=serialize(x); agent=session.get(User,agent_id) if agent_id else None; item["agent_id"]=agent_id; item["agent_name"]=agent.name if agent else "未分配"; pviews=premium_account_view(session, x); item["premium_balance_total"]=amount(sum(v["available"] for v in pviews)); item["premium_recharged"]=amount(sum(v["recharged"] for v in pviews)); item["premium_consumed"]=amount(sum(v["consumed"] for v in pviews)); uview=usage_account_view(session,x); item["usage_recharged"]=uview["recharged"]; item["usage_consumed"]=uview["consumed"]; item["usage_available"]=uview["available"]; result.append(item)
    return result

@router.post("/enterprises", dependencies=[Depends(require_role("admin", detail="仅总后台可新增投保单位"))])
def add_enterprise(data: EnterpriseIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if data.agent_id is not None:
        agent = session.get(User, data.agent_id)
        if not agent or agent.role != "salesperson": raise HTTPException(404, "业务员不存在")
    item = Enterprise(**data.model_dump()); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "enterprise", str(item.id)); return serialize(item)

@router.patch("/enterprises/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可审核投保单位"))])
def enterprise_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "企业不存在")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "enterprise", str(item.id), status_value); return serialize(item)

@router.patch("/enterprises/{item_id}")
def update_enterprise(item_id: int, data: EnterpriseUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权操作投保单位")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权操作该单位")
    if data.agent_id is not None:
        agent = session.get(User, data.agent_id)
        if not agent or agent.role != "salesperson": raise HTTPException(404, "业务员不存在")
        existing = session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id == item_id)).all()
        if existing and any(x.agent_id != data.agent_id for x in existing):
            raise HTTPException(409, "一个投保单位只能关联一个业务员；该单位已关联其他业务员")
    for key, value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "enterprise", str(item.id)); return serialize(item)

@router.delete("/enterprises/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可删除投保单位"))])
def delete_enterprise(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if session.scalar(select(InsuredPerson.id).where(InsuredPerson.enterprise_id == item_id).limit(1)) or session.scalar(select(Policy.id).where(Policy.enterprise_id == item_id).limit(1)): raise HTTPException(409, "该单位已有参保人员或保单，不能删除；请先停保并归档")
    session.delete(item); session.commit(); audit(session, user, "delete", "enterprise", str(item_id)); return {"ok": True}

@router.post("/enterprises/{item_id}/recharge", dependencies=[Depends(require_role("admin", detail="企业账户不支持自助充值，请联系平台完成充值审核"))])
def recharge_enterprise(item_id: int, data: RechargeIn, user: User = Depends(current_user), session: Session = Depends(db)):
    # SYSTEM-DESIGN-V4.md Phase 0 stop-loss item #2 ("禁用企业直接充值接口"):
    # this endpoint used to let a logged-in enterprise user credit their own
    # balance with zero payment verification. Restricted to admin-only as a
    # manual ops tool until the real Payment Order + Ledger flow (already
    # exposed via /api/payments + /api/payments/callback, but not yet wired
    # into any frontend) replaces it.
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if data.account not in {"premium", "usage"}: raise HTTPException(400, "账户类型不合法")
    if data.account == "premium": raise HTTPException(400, "保费账户充值请使用「账户充值」页面提交充值申请，走审核流程")
    item.usage_balance += data.amount
    post_ledger_entry(session, item, data.account, "credit", data.amount, "manual_recharge", str(item_id), user)
    session.commit(); audit(session, user, "recharge", "enterprise", str(item_id), f"{data.account}:{data.amount}"); return serialize(item)

@router.get("/enterprises/{item_id}/premium-accounts")
def enterprise_premium_accounts(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权查看该单位账户")
    if not session.get(Enterprise, item_id): raise HTTPException(404, "投保单位不存在")
    return premium_accounts_for_enterprise(session, item_id)

@router.get("/enterprises/{item_id}/ledger")
def enterprise_ledger(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Enterprise, item_id)
    if not item: raise HTTPException(404, "投保单位不存在")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权查看该单位账本")
    rows = session.scalars(select(LedgerEntry).where(LedgerEntry.enterprise_id == item_id).order_by(LedgerEntry.id.desc())).all()
    return {"entries": [ledger_dict(x, session) for x in rows], "reconciliation": reconcile_enterprise_ledger(session, item)}

@router.get("/enterprises/{item_id}/admins")
def enterprise_admins(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    if user.role not in {"admin","enterprise"}: raise HTTPException(403,"无权查看单位管理员")
    if user.role == "enterprise" and user.enterprise_id != item_id: raise HTTPException(403, "无权查看该单位")
    return [{"id": x.id, "username": x.username, "name": x.name, "phone": x.phone, "active": x.active} for x in session.scalars(select(User).where(User.enterprise_id == item_id, User.role == "enterprise"))]

@router.post("/enterprises/{item_id}/admins", dependencies=[Depends(require_role("admin", detail="仅总后台可管理单位管理员"))])
def add_enterprise_admin(item_id: int, data: AgentIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(Enterprise, item_id): raise HTTPException(404, "投保单位不存在")
    if session.scalar(select(User).where(User.username == data.username)): raise HTTPException(409, "账号已存在")
    # The first admin created for an enterprise is its owner; without this the
    # authoritative is_enterprise_owner() returns False, denying them operator
    # management and every employer-scoped read. A later admin is a project
    # manager — one active owner per enterprise (mirrors seed.py's invariant and
    # the single-primary-manager design).
    has_owner = session.scalar(select(User.id).where(User.enterprise_id == item_id, User.role == "enterprise", User.is_owner.is_(True)).limit(1)) is not None
    is_owner = not has_owner
    item=User(username=data.username,password_hash=pwd.hash(data.password),name=data.name,phone=data.phone,role="enterprise",enterprise_id=item_id,is_owner=is_owner,enterprise_role="owner" if is_owner else "project_manager");session.add(item);session.commit();session.refresh(item);audit(session,user,"create","enterprise_admin",str(item.id));return {"id":item.id,"username":item.username,"name":item.name,"phone":item.phone,"active":item.active,"is_owner":item.is_owner,"enterprise_role":item.enterprise_role}

@router.get("/enterprises/{item_id}/products")
def enterprise_products(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    if user.role=="enterprise" and user.enterprise_id!=item_id: raise HTTPException(403,"无权查看该单位")
    if not session.get(Enterprise,item_id): raise HTTPException(404,"投保单位不存在")
    rows=[]
    for x in session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id==item_id).order_by(AgentCommission.id.desc())):
        plan=session.get(InsurancePlan,x.plan_id);agent=session.get(User,x.agent_id); people=session.query(InsuredPerson).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).filter(InsuredPerson.enterprise_id==item_id,WorkPosition.plan_id==x.plan_id,InsuredPerson.status!='stopped').count(); premium=session.query(Policy).filter(Policy.enterprise_id==item_id,Policy.plan_id==x.plan_id).with_entities(Policy.premium).all(); rows.append(strip_internal_pricing({"id":x.id,"product":plan.name if plan else "","insurer":plan.insurer if plan else "","agent":agent.name if agent else "","commission_rate":x.rate,"insured_count":people,"premium_total":sum(float(p[0] or 0) for p in premium),"status":x.status,**(pricing_snapshot(plan,x) if plan else {})},user))
    return rows
