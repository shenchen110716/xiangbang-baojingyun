from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import AgentCommission, InsurancePlan, PlanTier, Policy, User, WorkPosition
from ..schemas import PlanIn, PlanTierIn, PlanUpdate
from ..services import plan_dict, serialize, strip_internal_pricing

router = APIRouter(prefix="/api", tags=["plans"])


@router.get("/plans")
def plans(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt = select(InsurancePlan).order_by(InsurancePlan.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        allowed = select(AgentCommission.plan_id).where(AgentCommission.enterprise_id == user.enterprise_id)
        position_plans=select(WorkPosition.plan_id).where(WorkPosition.enterprise_id==user.enterprise_id,WorkPosition.plan_id.is_not(None))
        stmt = stmt.where(or_(InsurancePlan.id.in_(allowed),InsurancePlan.id.in_(position_plans)))
        items = session.scalars(stmt).all()
        relations = {}
        for r in session.scalars(select(AgentCommission).where(AgentCommission.enterprise_id == user.enterprise_id, AgentCommission.status == "active").order_by(AgentCommission.id.asc())):
            relations[r.plan_id] = r
        return [strip_internal_pricing(plan_dict(x, relations.get(x.id)), user) for x in items]
    return [strip_internal_pricing(plan_dict(x), user) for x in session.scalars(stmt)]

@router.post("/plans")
def add_plan(data: PlanIn, user: User = Depends(current_user), session: Session = Depends(db)):
    # NOTE: role check stays inline (not a dependencies=[require_role(...)]) because
    # tests/system_smoke.py calls this function directly and asserts on the 403 it raises.
    if user.role != "admin": raise HTTPException(403,"仅总后台可新增保险方案")
    values=data.model_dump()
    if values['effective_mode']=='immediate': values['billing_mode']='daily'
    item = InsurancePlan(**values); session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "plan", str(item.id)); return plan_dict(item)

@router.get("/plan-tiers")
def plan_tiers(plan_id: Optional[int] = None, user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(PlanTier).order_by(PlanTier.id.desc())
    if user.role=='enterprise' and user.enterprise_id:
        allowed=select(AgentCommission.plan_id).where(AgentCommission.enterprise_id==user.enterprise_id)
        stmt=stmt.where(PlanTier.plan_id.in_(allowed))
    if plan_id: stmt=stmt.where(PlanTier.plan_id==plan_id)
    return [serialize(x) for x in session.scalars(stmt)]

@router.post("/plan-tiers", dependencies=[Depends(require_role("admin", detail="仅总后台可维护类别价格"))])
def add_plan_tier(data: PlanTierIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if not session.get(InsurancePlan,data.plan_id): raise HTTPException(404,"保险方案不存在")
    item=PlanTier(**data.model_dump());session.add(item);session.commit();session.refresh(item);audit(session,user,"create","plan_tier",str(item.id));return serialize(item)

@router.patch("/plans/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可维护保险方案"))])
def plan_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    if status_value not in {"active", "paused"}: raise HTTPException(400, "状态不合法")
    item.status = status_value; session.commit(); audit(session, user, "status_change", "plan", str(item.id), status_value); return serialize(item)

@router.patch("/plans/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可维护保险方案"))])
def update_plan(item_id: int, data: PlanUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    values=data.model_dump(exclude_unset=True)
    if values.get('effective_mode')=='immediate' or (item.effective_mode=='immediate' and values.get('effective_mode') is None): values['billing_mode']='daily'
    for key, value in values.items():
        if value is not None: setattr(item, key, value)
    session.commit(); audit(session, user, "update", "plan", str(item.id)); return plan_dict(item)

@router.delete("/plans/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可删除保险方案"))])
def delete_plan(item_id: int, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(InsurancePlan, item_id)
    if not item: raise HTTPException(404, "方案不存在")
    used = session.scalar(select(Policy.id).where(Policy.plan_id == item_id).limit(1))
    if used: raise HTTPException(409, "该方案已有参保人员或保单使用，不能删除；请先暂停方案")
    session.delete(item); session.commit(); audit(session, user, "delete", "plan", str(item_id)); return {"ok": True, "deleted_id": item_id}
