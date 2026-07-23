"""Admin-only 保司主体管理 — CRUD over Insurer, pending-edit review, merge tool.

Separate from routers/plans.py (which manages InsurancePlan, the 保险产品 the
old PlansAdminView.vue page calls "保险公司" in its title): this router
manages the Insurer *entity* introduced 2026-07-24 to back the insurer login
portal's data isolation.
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user, pwd
from ..models import Insurer, InsurancePlan, InsurerAccountLink, User
from ..schemas.insurer import InsurerAccountIn, InsurerEditReviewIn, InsurerIn, InsurerMergeIn, InsurerUpdate
from ..services import serialize

router = APIRouter(prefix="/api", tags=["insurers"])

_ADMIN = require_role("admin", detail="仅平台管理员可管理保险公司")


@router.get("/insurers", dependencies=[Depends(_ADMIN)])
def insurers(session: Session = Depends(db)):
    return [serialize(x) for x in session.scalars(select(Insurer).order_by(Insurer.id.desc()))]


@router.post("/insurers", dependencies=[Depends(_ADMIN)])
def add_insurer(data: InsurerIn, user: User = Depends(current_user), session: Session = Depends(db)):
    name = data.name.strip()
    if session.scalar(select(Insurer.id).where(Insurer.name == name).limit(1)):
        raise HTTPException(409, "该保司名称已存在，如需处理重复录入请使用合并保司工具")
    item = Insurer(name=name, contact=data.contact.strip(), phone=data.phone.strip())
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer", str(item.id))
    return serialize(item)


@router.patch("/insurers/{item_id}", dependencies=[Depends(_ADMIN)])
def update_insurer(item_id: int, data: InsurerUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, item_id)
    if not item: raise HTTPException(404, "保险公司不存在")
    for key, value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item, key, value.strip() if isinstance(value, str) else value)
    session.commit(); audit(session, user, "update", "insurer", str(item.id))
    return serialize(item)


@router.get("/insurers/pending-edits", dependencies=[Depends(_ADMIN)])
def pending_insurer_edits(session: Session = Depends(db)):
    stmt = select(Insurer).where(Insurer.pending_submitted_at.isnot(None)).order_by(Insurer.pending_submitted_at.asc())
    return [serialize(x) for x in session.scalars(stmt)]


@router.post("/insurers/{item_id}/review-edit", dependencies=[Depends(_ADMIN)])
def review_insurer_edit(item_id: int, data: InsurerEditReviewIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, item_id)
    if not item: raise HTTPException(404, "保险公司不存在")
    if item.pending_submitted_at is None: raise HTTPException(409, "该保司当前没有待审核的信息变更")
    if data.approve:
        if item.pending_name is not None: item.name = item.pending_name
        if item.pending_contact is not None: item.contact = item.pending_contact
        if item.pending_phone is not None: item.phone = item.pending_phone
    item.pending_name = None
    item.pending_contact = None
    item.pending_phone = None
    item.pending_submitted_at = None
    session.commit()
    audit(session, user, "review", "insurer", str(item.id), "approved" if data.approve else f"rejected:{data.reject_reason}")
    return serialize(item)


@router.post("/insurers/merge", dependencies=[Depends(_ADMIN)])
def merge_insurers(data: InsurerMergeIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if data.target_id in data.source_ids:
        raise HTTPException(400, "保留目标不能同时出现在被合并列表里")
    target = session.get(Insurer, data.target_id)
    if not target: raise HTTPException(404, "保留目标保司不存在")
    sources = []
    for source_id in data.source_ids:
        source = session.get(Insurer, source_id)
        if not source: raise HTTPException(404, f"待合并保司 {source_id} 不存在")
        sources.append(source)
    source_ids = [s.id for s in sources]
    session.query(InsurancePlan).filter(InsurancePlan.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    session.query(InsurerAccountLink).filter(InsurerAccountLink.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    session.query(User).filter(User.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    for source in sources:
        session.delete(source)
    session.commit()
    audit(session, user, "merge", "insurer", str(target.id), f"merged={source_ids}")
    return serialize(target)


def _account_dict(item: User) -> dict:
    return {"id": item.id, "username": item.username, "name": item.name, "active": item.active, "status": item.status, "created_at": item.created_at}


@router.get("/insurers/{item_id}/accounts", dependencies=[Depends(_ADMIN)])
def insurer_accounts(item_id: int, session: Session = Depends(db)):
    if not session.get(Insurer, item_id): raise HTTPException(404, "保险公司不存在")
    stmt = select(User).where(User.role == "insurer", User.insurer_id == item_id).order_by(User.id.desc())
    return [_account_dict(x) for x in session.scalars(stmt)]


@router.post("/insurers/{item_id}/accounts", dependencies=[Depends(_ADMIN)])
def create_insurer_account(item_id: int, data: InsurerAccountIn, user: User = Depends(current_user), session: Session = Depends(db)):
    insurer = session.get(Insurer, item_id)
    if not insurer: raise HTTPException(404, "保险公司不存在")
    if session.scalar(select(User.id).where(User.username == data.username).limit(1)):
        raise HTTPException(409, "该用户名已存在")
    item = User(username=data.username, password_hash=pwd.hash(data.password), name=data.name.strip() or insurer.name, role="insurer", insurer_id=insurer.id)
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer_account", str(item.id), f"insurer_id={insurer.id}")
    return _account_dict(item)


@router.patch("/insurers/accounts/{account_id}/status", dependencies=[Depends(_ADMIN)])
def insurer_account_status(account_id: int, status_value: str = Query(..., alias="status"), user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(User, account_id)
    if not item or item.role != "insurer": raise HTTPException(404, "保司账号不存在")
    item.status = status_value; item.active = status_value == "active"
    item.session_version += 1
    session.commit(); audit(session, user, "status_change", "insurer_account", str(item.id), status_value)
    return _account_dict(item)
