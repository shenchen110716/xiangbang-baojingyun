from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user, pwd
from ..models import AgentCommission, Enterprise, InsurancePlan, User
from ..schemas import AgentIn, CommissionIn, CommissionUpdate
from ..services import (
    agent_commission_rows, agent_commission_summary, commission_dict,
    pricing_snapshot, serialize, validate_commission_price,
)

router = APIRouter(prefix="/api", tags=["agents"])


@router.get("/agents", dependencies=[Depends(require_role("admin", detail="仅总后台可管理业务员"))])
def agents(user: User = Depends(current_user), session: Session = Depends(db)):
    return [{"id": x.id, "username": x.username, "name": x.name, "phone": x.phone, "role": x.role, "active": x.active, "status": x.status, "created_at": x.created_at, **agent_commission_summary(session, x.id)} for x in session.scalars(select(User).where(User.role == "salesperson").order_by(User.id.desc()))]

@router.get("/agents/{item_id}/commissions", dependencies=[Depends(require_role("admin", detail="仅总后台可查看业务员佣金"))])
def agent_commissions_detail(item_id: int, session: Session = Depends(db)):
    if not session.get(User, item_id): raise HTTPException(404, "业务员不存在")
    return agent_commission_rows(session, item_id)

@router.post("/agents", dependencies=[Depends(require_role("admin", detail="仅总后台可管理业务员"))])
def add_agent(data: AgentIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if session.scalar(select(User).where(User.username == data.username)): raise HTTPException(409, "业务员账号已存在")
    item = User(username=data.username, password_hash=pwd.hash(data.password), name=data.name, phone=data.phone, role="salesperson")
    session.add(item); session.commit(); session.refresh(item); audit(session, user, "create", "salesperson", str(item.id)); return {"id": item.id, "username": item.username, "name": item.name, "role": item.role, "active": item.active}

@router.patch("/agents/{item_id}/status", dependencies=[Depends(require_role("admin", detail="仅总后台可管理业务员"))])
def agent_status(item_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(User, item_id)
    if not item or item.role != "salesperson": raise HTTPException(404, "业务员不存在")
    item.status = status_value; item.active = status_value == "active"; session.commit(); audit(session, user, "status_change", "salesperson", str(item.id), status_value); return {"ok": True, "status": item.status}

@router.get("/agents/me", dependencies=[Depends(require_role("salesperson", detail="仅业务员账号可查看"))])
def my_commissions(user: User = Depends(current_user), session: Session = Depends(db)):
    return {"summary": agent_commission_summary(session, user.id), "rows": agent_commission_rows(session, user.id)}

@router.get("/agent-commissions", dependencies=[Depends(require_role("admin", detail="仅总后台可查看业务员佣金"))])
def agent_commissions(session: Session = Depends(db)):
    return [commission_dict(x,session) for x in session.scalars(select(AgentCommission).order_by(AgentCommission.id.desc()))]

@router.post("/agent-commissions", dependencies=[Depends(require_role("admin", detail="仅总后台可配置佣金"))])
def add_agent_commission(data: CommissionIn, user: User = Depends(current_user), session: Session = Depends(db)):
    agent=session.get(User,data.agent_id); enterprise=session.get(Enterprise,data.enterprise_id); plan=session.get(InsurancePlan,data.plan_id)
    if not agent or agent.role != "salesperson": raise HTTPException(404,"业务员不存在")
    if not enterprise or not plan: raise HTTPException(404,"投保单位或产品方案不存在")
    if enterprise.agent_id is not None and enterprise.agent_id != data.agent_id: raise HTTPException(409,"一个投保单位只能关联一个业务员；该单位已关联其他业务员")
    mode,sale_price=validate_commission_price(data,plan);values=data.model_dump();values['mode']=mode;values['sale_price']=sale_price;values['markup_amount']=max(0,sale_price-pricing_snapshot(plan)['minimum_sale_price']) if mode=='price' else 0
    if mode=='price': values['rate']=0
    item=AgentCommission(**values);session.add(item)
    if enterprise.agent_id is None: enterprise.agent_id = data.agent_id
    session.commit();session.refresh(item);audit(session,user,"create","agent_commission",str(item.id));return serialize(item)

@router.patch("/agent-commissions/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可修改佣金关系"))])
def update_agent_commission(item_id:int,data:CommissionUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(AgentCommission,item_id)
    if not item: raise HTTPException(404,"佣金关系不存在")
    values=data.model_dump(exclude_unset=True)
    for k,v in values.items():
        if v is not None: setattr(item,k,v)
    plan=session.get(InsurancePlan,item.plan_id);mode,sale_price=validate_commission_price(item,plan);item.mode=mode;item.sale_price=sale_price;item.markup_amount=max(0,sale_price-pricing_snapshot(plan)['minimum_sale_price']) if mode=='price' else 0
    if mode=='price': item.rate=0
    session.commit();audit(session,user,"update","agent_commission",str(item.id));return commission_dict(item,session)

@router.delete("/agent-commissions/{item_id}", dependencies=[Depends(require_role("admin", detail="仅总后台可删除佣金关系"))])
def delete_agent_commission(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(AgentCommission,item_id)
    if not item: raise HTTPException(404,"佣金关系不存在")
    session.delete(item); session.commit(); audit(session,user,"delete","agent_commission",str(item_id)); return {"ok":True}
