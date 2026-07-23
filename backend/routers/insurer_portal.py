"""Insurer portal APIs (2026-07-24 design). Every endpoint here requires
require_insurer_scope and derives insurer_id from the JWT — same identity
discipline as agent_portal.py: a supplied insurer_id in a query/body is never
honoured, only the authenticated user's own insurer_id.
"""
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_insurer_scope
from ..core.security import current_user
from ..models import Insurer, InsuredPerson, User, WorkPosition
from ..schemas.insurer import InsurerProfileIn
from ..services import insurer_plan_ids, insurer_settlement_summary, serialize

router = APIRouter(prefix="/api/insurer-portal", tags=["insurer-portal"])

_INSURER = require_insurer_scope


@router.get("/profile", dependencies=[Depends(_INSURER)])
def profile(user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, user.insurer_id)
    if not item: raise HTTPException(404, "保司信息不存在")
    return serialize(item)


@router.patch("/profile", dependencies=[Depends(_INSURER)])
def submit_profile_edit(data: InsurerProfileIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, user.insurer_id)
    if not item: raise HTTPException(404, "保司信息不存在")
    values = data.model_dump(exclude_unset=True)
    if not values: raise HTTPException(400, "请至少填写一项要修改的信息")
    item.pending_name = values.get("name", item.name)
    item.pending_contact = values.get("contact", item.contact)
    item.pending_phone = values.get("phone", item.phone)
    item.pending_credit_code = values.get("credit_code", item.credit_code)
    item.pending_email = values.get("email", item.email)
    item.pending_address = values.get("address", item.address)
    item.pending_submitted_at = datetime.now(timezone.utc)
    session.commit()
    return serialize(item)


@router.get("/settlement", dependencies=[Depends(_INSURER)])
def settlement(user: User = Depends(current_user), session: Session = Depends(db)):
    return insurer_settlement_summary(session, user.insurer_id, user)


@router.get("/insured", dependencies=[Depends(_INSURER)])
def insured_for_review(user: User = Depends(current_user), session: Session = Depends(db)):
    plan_ids = insurer_plan_ids(session, user.insurer_id)
    if not plan_ids:
        return []
    stmt = select(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).where(
        WorkPosition.plan_id.in_(plan_ids)).order_by(InsuredPerson.id.desc())
    return [serialize(x) for x in session.scalars(stmt)]
