"""Insurer portal APIs (2026-07-24 design). Every endpoint here requires
require_insurer_scope and derives insurer_id from the JWT — same identity
discipline as agent_portal.py: a supplied insurer_id in a query/body is never
honoured, only the authenticated user's own insurer_id.
"""
import io
import re
from datetime import datetime, timezone

import openpyxl
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_insurer_scope
from ..core.security import current_user
from ..models import Insurer, InsuredPerson, User, WorkPosition
from ..schemas.insurer import InsurerProfileIn
from ..services import insurer_monthly_premium_rows, insurer_monthly_premium_summary, insurer_plan_ids, insurer_settlement_summary, serialize

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


_MONTH_PATTERN = re.compile(r"^(\d{4})-(0[1-9]|1[0-2])$")


def _parse_month(month: str) -> tuple[int, int, str]:
    # 严格正则而不是 int() 直接转换：int() 会悄悄吃掉首尾空白/控制字符（比如
    # "07\n" 也能转成 7），一旦校验通过又把原始 month 字符串继续往下传，这些
    # 字符就会被裸拼进 Excel sheet 名和 Content-Disposition 响应头。这里返回
    # 的第三项是用校验后的 (year, month) 重新拼出的规范化字符串，后续所有
    # 拼接一律用这个，不再使用调用方传入的原始 month。
    match = _MONTH_PATTERN.match(month)
    if not match: raise HTTPException(400, "月份格式应为 yyyy-MM")
    year, month_num = int(match.group(1)), int(match.group(2))
    if not (2000 <= year <= 2100): raise HTTPException(400, "月份年份超出支持范围")
    return year, month_num, f"{year:04d}-{month_num:02d}"


@router.get("/settlement/monthly", dependencies=[Depends(_INSURER)])
def settlement_monthly(months: int = Query(12, ge=1, le=24), user: User = Depends(current_user), session: Session = Depends(db)):
    return insurer_monthly_premium_summary(session, user.insurer_id, months)


@router.get("/settlement/monthly/{month}", dependencies=[Depends(_INSURER)])
def settlement_monthly_detail(month: str, user: User = Depends(current_user), session: Session = Depends(db)):
    year, month_num, _ = _parse_month(month)
    return insurer_monthly_premium_rows(session, user.insurer_id, year, month_num)


@router.get("/settlement/monthly/{month}/export", dependencies=[Depends(_INSURER)])
def settlement_monthly_export(month: str, user: User = Depends(current_user), session: Session = Depends(db)):
    year, month_num, clean_month = _parse_month(month)
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


@router.get("/insured", dependencies=[Depends(_INSURER)])
def insured_for_review(user: User = Depends(current_user), session: Session = Depends(db)):
    plan_ids = insurer_plan_ids(session, user.insurer_id)
    if not plan_ids:
        return []
    stmt = select(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).where(
        WorkPosition.plan_id.in_(plan_ids)).order_by(InsuredPerson.id.desc())
    return [serialize(x) for x in session.scalars(stmt)]
