"""Agent portal APIs (v4.2 §14.4).

Every endpoint is salesperson-only and derives agent_id from the JWT. A supplied
agent_id is ignored, never honoured (§17.1): an identity a query parameter can
move is one somebody forgets to check. The product view is an allow-list schema
(§5.1); list, summary and export all call the one shared query so a total cannot
drift from its detail (§14.4).
"""
import io

import openpyxl
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import (
    AgentCommissionPayment,
    AgentCommissionPaymentAllocation,
    AgentCommissionStatement,
    AgentCommissionStatementItem,
    User,
)
from ..schemas.agent_portal import (
    AgentBalancesOut,
    AgentCommissionListOut,
    AgentCommissionSummaryOut,
    AgentPaymentOut,
    AgentProductListOut,
    AgentStatementOut,
)
from ..services.agent_portal_query import CommissionFilters, commission_rows, commission_summary
from ..services.agent_settlement import agent_balances, portal_products
from ..services.commissions import agent_commission_summary

router = APIRouter(prefix="/api", tags=["agent-portal"])

_SALESPERSON = require_role("salesperson", detail="仅业务员账号可访问")


def _filters(enterprise_id, insurer, plan_id) -> CommissionFilters:
    return CommissionFilters(enterprise_id=enterprise_id, insurer=insurer, plan_id=plan_id)


@router.get("/agent-portal/products", response_model=AgentProductListOut,
            dependencies=[Depends(_SALESPERSON)])
def agent_products(user: User = Depends(current_user), session: Session = Depends(db)):
    return {"items": portal_products(session, user)}


@router.get("/agent-portal/commissions/summary", response_model=AgentCommissionSummaryOut,
            dependencies=[Depends(_SALESPERSON)])
def commissions_summary(enterprise_id: int | None = Query(None),
                        insurer: str | None = Query(None),
                        plan_id: int | None = Query(None),
                        user: User = Depends(current_user), session: Session = Depends(db)):
    return commission_summary(session, user.id, _filters(enterprise_id, insurer, plan_id))


@router.get("/agent-portal/commissions/details", response_model=AgentCommissionListOut,
            dependencies=[Depends(_SALESPERSON)])
def commissions_details(enterprise_id: int | None = Query(None),
                        insurer: str | None = Query(None),
                        plan_id: int | None = Query(None),
                        user: User = Depends(current_user), session: Session = Depends(db)):
    return {"items": commission_rows(session, user.id, _filters(enterprise_id, insurer, plan_id))}


@router.get("/agent-portal/commissions/export", dependencies=[Depends(_SALESPERSON)])
def commissions_export(enterprise_id: int | None = Query(None),
                       insurer: str | None = Query(None),
                       plan_id: int | None = Query(None),
                       user: User = Depends(current_user), session: Session = Depends(db)):
    """Same query as list and summary (§14.4), so the export row count and total
    match what the agent sees on screen."""
    rows = commission_rows(session, user.id, _filters(enterprise_id, insurer, plan_id))
    book = openpyxl.Workbook()
    sheet = book.active
    sheet.title = "我的佣金"
    header = ["投保单位", "保险产品", "保司", "计佣方式", "状态", "在保人数",
              "单笔佣金", "累计佣金", "计提截至"]
    sheet.append(header)
    for cell in sheet[1]:
        cell.font = openpyxl.styles.Font(bold=True)
    for row in rows:
        sheet.append([row["enterprise_name"], row["plan_name"], row["insurer"],
                      row["mode"], row["status"], row["insured_count"],
                      row["unit_amount"], row["amount"], row["accrual_as_of"]])
    output = io.BytesIO()
    book.save(output)
    book.close()
    return StreamingResponse(
        iter([output.getvalue()]),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": "attachment; filename=my-commissions.xlsx",
                 "X-Row-Count": str(len(rows))},
    )


@router.get("/agent-portal/balances", response_model=AgentBalancesOut,
            dependencies=[Depends(_SALESPERSON)])
def balances(user: User = Depends(current_user), session: Session = Depends(db)):
    return agent_balances(session, user.id)


def _serialize_statement(session: Session, statement: AgentCommissionStatement) -> dict:
    items = session.scalars(select(AgentCommissionStatementItem).where(
        AgentCommissionStatementItem.statement_id == statement.id)
        .order_by(AgentCommissionStatementItem.id))
    return {
        "id": statement.id, "statement_no": statement.statement_no,
        "period_start": statement.period_start, "period_end": statement.period_end,
        "currency": statement.currency, "total_amount": statement.total_amount,
        "status": statement.status, "confirmed_at": statement.confirmed_at,
        "created_at": statement.created_at,
        "items": [{
            "id": i.id, "source_type": i.source_type, "plan_id": i.plan_id,
            "enterprise_id": i.enterprise_id, "amount": i.amount, "status": i.status,
            "adjusts_item_id": i.adjusts_item_id, "created_at": i.created_at,
        } for i in items],
    }


@router.get("/agent-portal/statements", response_model=list[AgentStatementOut],
            dependencies=[Depends(_SALESPERSON)])
def statements(user: User = Depends(current_user), session: Session = Depends(db)):
    rows = session.scalars(select(AgentCommissionStatement).where(
        AgentCommissionStatement.agent_id == user.id,
        AgentCommissionStatement.status != "draft")
        .order_by(AgentCommissionStatement.id.desc()))
    return [_serialize_statement(session, s) for s in rows]


@router.get("/agent-portal/statements/{item_id}", response_model=AgentStatementOut,
            dependencies=[Depends(_SALESPERSON)])
def statement_detail(item_id: int, user: User = Depends(current_user),
                     session: Session = Depends(db)):
    statement = session.get(AgentCommissionStatement, item_id)
    if not statement or statement.agent_id != user.id:
        # 404 not 403: confirming another agent's statement id exists is itself
        # a leak (§17.1).
        raise HTTPException(404, "结算单不存在")
    if statement.status == "draft":
        raise HTTPException(404, "结算单不存在")
    return _serialize_statement(session, statement)


@router.get("/agent-portal/payments", response_model=list[AgentPaymentOut],
            dependencies=[Depends(_SALESPERSON)])
def payments(user: User = Depends(current_user), session: Session = Depends(db)):
    rows = session.scalars(select(AgentCommissionPayment).where(
        AgentCommissionPayment.agent_id == user.id)
        .order_by(AgentCommissionPayment.id.desc()))
    result = []
    for payment in rows:
        allocated = session.scalar(
            select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0.0))
            .where(AgentCommissionPaymentAllocation.payment_id == payment.id)) or 0.0
        result.append({
            "id": payment.id, "amount": payment.amount, "channel": payment.channel,
            "transaction_no": payment.transaction_no, "paid_at": payment.paid_at,
            "voucher_url": payment.voucher_url, "allocated_amount": round(float(allocated), 2),
            "created_at": payment.created_at,
        })
    return result
