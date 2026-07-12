from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_operator_manager
from ..core.security import current_user, pwd
from ..models import Enterprise, User
from ..schemas import OperatorIn, OperatorUpdate
from ..services.operators import operator_dict

router = APIRouter(prefix="/api", tags=["operators"])


@router.get("/operators")
def operators(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(User).where(User.role=="enterprise").order_by(User.is_owner.desc(),User.id.asc())
    if user.role=="enterprise":
        if not user.enterprise_id: return []
        stmt=stmt.where(User.enterprise_id==user.enterprise_id)
    elif user.role!="admin": raise HTTPException(403,"无权查看操作员")
    return [operator_dict(item,session) for item in session.scalars(stmt)]

@router.post("/operators")
def add_operator(data:OperatorIn,user:User=Depends(require_operator_manager),session:Session=Depends(db)):
    enterprise_id=user.enterprise_id if user.role=="enterprise" else data.enterprise_id
    if not enterprise_id or not session.get(Enterprise,enterprise_id): raise HTTPException(400,"请选择有效投保单位")
    if session.scalar(select(User).where(User.username==data.username)): raise HTTPException(409,"登录账号已存在")
    item=User(username=data.username.strip(),password_hash=pwd.hash(data.password),name=data.name.strip(),phone=data.phone.strip(),role="enterprise",enterprise_id=enterprise_id,is_owner=False,active=True,status="active")
    session.add(item);session.commit();session.refresh(item);audit(session,user,"create","operator",str(item.id));return operator_dict(item,session)

@router.patch("/operators/{item_id}")
def update_operator(item_id:int,data:OperatorUpdate,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(User,item_id)
    if not item or item.role!="enterprise": raise HTTPException(404,"操作员不存在")
    if user.role=="enterprise":
        if not user.is_owner: raise HTTPException(403,"仅单位主管可管理操作员")
        if item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权管理其他单位操作员")
    elif user.role!="admin": raise HTTPException(403,"无权管理操作员")
    if item.id==user.id and data.active is False: raise HTTPException(400,"不能停用当前登录账号")
    if item.is_owner and data.active is False: raise HTTPException(400,"单位主管不能停用")
    values=data.model_dump(exclude_unset=True)
    if values.get("name") is not None: item.name=values["name"].strip()
    if values.get("phone") is not None: item.phone=values["phone"].strip()
    if values.get("password"): item.password_hash=pwd.hash(values["password"])
    if values.get("active") is not None: item.active=values["active"];item.status="active" if item.active else "inactive"
    session.commit();audit(session,user,"update","operator",str(item.id));return operator_dict(item,session)
