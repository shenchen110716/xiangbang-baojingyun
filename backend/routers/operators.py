from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_operator_manager
from ..core.security import current_user, pwd
from ..models import Enterprise, User, UserEmployerScope
from ..schemas import OperatorIn, OperatorUpdate
from ..services.operators import account_has_data, operator_dict

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
    item=User(username=data.username.strip(),password_hash=pwd.hash(data.password),name=data.name.strip(),phone=data.phone.strip(),role="enterprise",enterprise_id=enterprise_id,enterprise_role="project_manager",is_owner=False,active=True,status="active")
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
    if values.get("enterprise_id") is not None:
        if user.role!="admin": raise HTTPException(403,"仅平台端可调整所属单位")
        if item.is_owner: raise HTTPException(400,"单位主管不能更换所属单位")
        target=session.get(Enterprise,values["enterprise_id"])
        if not target: raise HTTPException(400,"目标投保单位不存在")
        item.enterprise_id=target.id
    if values.get("enterprise_role") is not None:
        requested_role=values["enterprise_role"]
        if user.role!="admin" and requested_role!="project_manager":
            raise HTTPException(403,"仅平台管理员可设置企业主管")
        if item.enterprise_role!=requested_role or item.is_owner!=(requested_role=="owner"):
            item.enterprise_role=requested_role
            item.is_owner=requested_role=="owner"
            item.session_version+=1
    if values.get("username") is not None and values["username"].strip()!=item.username:
        if user.role!="admin": raise HTTPException(403,"仅平台管理员可修改登录账号")
        if account_has_data(session,item): raise HTTPException(400,"该账号已产生业务数据，不能修改登录账号")
        if session.scalar(select(User).where(User.username==values["username"].strip(),User.id!=item.id)): raise HTTPException(409,"登录账号已存在")
        item.username=values["username"].strip();item.session_version+=1
    if values.get("name") is not None: item.name=values["name"].strip()
    if values.get("phone") is not None: item.phone=values["phone"].strip()
    if values.get("password"): item.password_hash=pwd.hash(values["password"]);item.session_version+=1
    if values.get("active") is not None:
        item.active=values["active"];item.status="active" if item.active else "inactive"
        if not item.active: item.session_version+=1
    session.commit();audit(session,user,"update","operator",str(item.id));return operator_dict(item,session)


@router.delete("/operators/{item_id}")
def delete_operator(item_id:int,user:User=Depends(current_user),session:Session=Depends(db)):
    """删除单位账号：仅限未产生有效业务数据的账号（无审计动作/授权/充值，主管则单位无参保人）。
    删除前清理其持有的用工单位授权。有数据的账号请改用停用，保留历史归属。"""
    item=session.get(User,item_id)
    if not item or item.role!="enterprise": raise HTTPException(404,"账号不存在")
    if user.role=="enterprise":
        if not user.is_owner: raise HTTPException(403,"仅单位主管可管理操作员")
        if item.enterprise_id!=user.enterprise_id: raise HTTPException(403,"无权管理其他单位操作员")
    elif user.role!="admin": raise HTTPException(403,"无权管理操作员")
    if item.id==user.id: raise HTTPException(400,"不能删除当前登录账号")
    if account_has_data(session,item): raise HTTPException(400,"该账号已产生业务数据，不能删除，请改用停用")
    for scope in session.scalars(select(UserEmployerScope).where(UserEmployerScope.user_id==item.id)):
        session.delete(scope)
    try:
        session.delete(item);session.flush()
    except IntegrityError:
        session.rollback();raise HTTPException(400,"该账号存在关联记录，不能删除，请改用停用")
    session.commit()
    audit(session,user,"delete","operator",str(item_id))
    return {"ok":True}
