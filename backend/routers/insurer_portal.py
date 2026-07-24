"""Insurer portal APIs (2026-07-24 design). Every endpoint here requires
require_insurer_scope and derives insurer_id from the JWT — same identity
discipline as agent_portal.py: a supplied insurer_id in a query/body is never
honoured, only the authenticated user's own insurer_id.
"""
import io
from datetime import datetime, timezone

import openpyxl
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_insurer_scope
from ..core.security import current_user
from ..models import Insurer, InsuredPerson, PolicyMember, User, WorkPosition
from ..schemas.insurer import InsurerProfileIn
from ..services import (
    effective_person_status, insurer_monthly_premium_rows, insurer_monthly_premium_summary,
    insurer_plan_ids, insurer_settlement_summary, parse_insurer_month, serialize,
)

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


@router.get("/settlement/monthly", dependencies=[Depends(_INSURER)])
def settlement_monthly(months: int = Query(12, ge=1, le=24), user: User = Depends(current_user), session: Session = Depends(db)):
    return insurer_monthly_premium_summary(session, user.insurer_id, months)


@router.get("/settlement/monthly/{month}", dependencies=[Depends(_INSURER)])
def settlement_monthly_detail(month: str, user: User = Depends(current_user), session: Session = Depends(db)):
    year, month_num, _ = parse_insurer_month(month)
    return insurer_monthly_premium_rows(session, user.insurer_id, year, month_num)


@router.get("/settlement/monthly/{month}/export", dependencies=[Depends(_INSURER)])
def settlement_monthly_export(month: str, user: User = Depends(current_user), session: Session = Depends(db)):
    year, month_num, clean_month = parse_insurer_month(month)
    rows = insurer_monthly_premium_rows(session, user.insurer_id, year, month_num)
    book = openpyxl.Workbook()
    sheet = book.active
    sheet.title = f"{clean_month}保费明细"
    header = ["姓名", "身份证号", "投保单位", "保单号", "参保时间", "停保时间", "参保天数", "单价", "合计保费"]
    sheet.append(header)
    for cell in sheet[1]:
        cell.font = openpyxl.styles.Font(bold=True)
    for row in rows:
        sheet.append([row["person_name"], row["id_number"], row["enterprise_name"], row["policy_no"],
                      row["effective_at"], row["terminated_at"] or "在保", row["billable_days"],
                      row["unit_price"], row["amount"]])
    output = io.BytesIO()
    book.save(output)
    book.close()
    return StreamingResponse(
        iter([output.getvalue()]),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f"attachment; filename=premium-{clean_month}.xlsx"},
    )


def _insurer_person_payload(session: Session, item: InsuredPerson) -> dict:
    # 复用 routers/insured.py::_person_payload 的口径：effective_at/terminated_at
    # 不是 InsuredPerson 自己的列，要从最近一条 PolicyMember 上取；status 也不是
    # 直接用原始列，要跑一遍 effective_person_status 才是"待生效"这类展示口径
    # 该有的真实状态，不然 serialize() 直出的 status 可能是过期值。
    payload = serialize(item)
    member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == item.id).order_by(PolicyMember.id.desc()))
    effective_at, terminated_at = (member.effective_at, member.terminated_at) if member else (None, None)
    payload["effective_at"], payload["terminated_at"] = effective_at, terminated_at
    payload["status"] = effective_person_status(item, terminated_at)
    return payload


@router.get("/insured", dependencies=[Depends(_INSURER)])
def insured_for_review(user: User = Depends(current_user), session: Session = Depends(db)):
    plan_ids = insurer_plan_ids(session, user.insurer_id)
    if not plan_ids:
        return []
    stmt = select(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).where(
        WorkPosition.plan_id.in_(plan_ids)).order_by(InsuredPerson.id.desc())
    return [_insurer_person_payload(session, x) for x in session.scalars(stmt)]
