"""Agent commission portal and settlement ledger (v4.2 §5.1, §5.2, §5.3).

Two separations shape this module.

**Reads are allow-listed, not deny-listed.** §5.1 was breached in production
once because the masking was subtractive: it hid the fields we knew were bad.
`portal_products` builds its response field by field, so a column added to
InsurancePlan tomorrow cannot appear here by accident. Internal cost, platform
profit and other agents' commissions are never in the dict to begin with.

**Money is append-only.** 已确认结算项不得原地改写 (§5.3): a confirmed amount is
evidence of what was agreed, so corrections arrive as adjustment or reversal
rows pointing at the original. The balance ceilings span tables and cannot be a
CHECK — `allocate` enforces them inside one transaction with the rows locked,
because an over-allocation means paying real money twice.
"""
import json
from datetime import date, datetime, timezone
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..core.business_time import business_now
from ..models import (
    AgentCommission,
    AgentCommissionPayment,
    AgentCommissionPaymentAllocation,
    AgentCommissionStatement,
    AgentCommissionStatementItem,
    InsurancePlan,
    User,
)
from .commissions import agent_commission_summary, commission_accrual
from .pricing import pricing_snapshot

# §5.1 业务员产品中心响应白名单。多一个字段就算泄漏。
PORTAL_PRODUCT_FIELDS = frozenset({
    "id", "insurer", "name", "coverage", "occupation_classes",
    "billing_mode", "effective_mode", "status",
    "min_sale_price", "my_commission_status",
})

_COMMISSION_STATUS_LABEL = {"rebate": "按比例返佣", "price": "按售价加价", "markup": "按售价加价"}
_UNCONFIGURED = "未配置"


def portal_products(session: Session, agent: User) -> list[dict]:
    """Every sellable product plus the platform's minimum sale price (§5.1).

    Products with no commission relation for this agent are included — the
    catalogue is what they sell from, not what they are already paid for.
    """
    relations = {
        row.plan_id: row
        for row in session.scalars(
            select(AgentCommission).where(AgentCommission.agent_id == agent.id,
                                          AgentCommission.status == "active"))
    }

    products = []
    for plan in session.scalars(
            select(InsurancePlan).where(InsurancePlan.status == "active")
            .order_by(InsurancePlan.id.desc())):
        relation = relations.get(plan.id)
        # pricing_snapshot computes the floor internally; only the minimum sale
        # price crosses into the response. The cost basis stays in this scope.
        minimum = pricing_snapshot(plan)["minimum_sale_price"]
        products.append({
            "id": plan.id,
            "insurer": plan.insurer,
            "name": plan.name,
            "coverage": plan.coverage,
            "occupation_classes": plan.occupation_classes,
            "billing_mode": plan.billing_mode,
            "effective_mode": plan.effective_mode,
            "status": plan.status,
            "min_sale_price": minimum,
            "my_commission_status": (
                _COMMISSION_STATUS_LABEL.get(relation.mode, "已配置") if relation
                else _UNCONFIGURED
            ),
        })
    return products


def portal_commission_summary(session: Session, agent: User, *, agent_id=None, **_ignored) -> dict:
    """§17.1 业务员不能通过传入 agent_id 查询他人数据.

    `agent_id` is accepted and ignored rather than rejected: rejecting confirms
    the other agent exists, and an identity that a query parameter can move is
    one somebody eventually forgets to re-check. Identity comes from the token.
    """
    balances = agent_balances(session, agent.id)
    legacy = agent_commission_summary(session, agent.id)
    return {"agent_id": agent.id, **balances,
            "relation_count": legacy.get("count", 0) if isinstance(legacy, dict) else 0}


# --- 结算账本 -----------------------------------------------------------

def _statement_no(session: Session, agent_id: int) -> str:
    seq = session.scalar(select(func.count(AgentCommissionStatement.id))) or 0
    return f"ST{business_now():%Y%m}-{agent_id}-{seq + 1:04d}"


def _require_admin(user: User) -> None:
    if getattr(user, "role", None) != "admin":
        raise HTTPException(403, "仅平台管理员可操作佣金结算")


def build_statement(session: Session, admin: User, *, agent_id: int,
                    period_start: date, period_end: date) -> AgentCommissionStatement:
    """Draft a statement from the agent's active commission relations.

    Each item freezes an amount snapshot now, so a later rate change restates
    nothing (§5.3).
    """
    _require_admin(admin)
    if period_end < period_start:
        raise HTTPException(400, "结算期间的结束日期不能早于开始日期")

    statement = AgentCommissionStatement(
        agent_id=agent_id, statement_no=_statement_no(session, agent_id),
        period_start=period_start, period_end=period_end, currency="CNY",
        total_amount=0, status="draft", created_at=business_now())
    session.add(statement)
    session.flush()

    total = 0.0
    for relation in session.scalars(
            select(AgentCommission).where(AgentCommission.agent_id == agent_id,
                                          AgentCommission.status == "active")):
        accrual = commission_accrual(session, relation, as_of=period_end)
        amount = round(float(accrual.get("amount") or accrual.get("total") or 0), 2)
        item = AgentCommissionStatementItem(
            statement_id=statement.id, source_type="accrual",
            plan_id=relation.plan_id, enterprise_id=relation.enterprise_id,
            period_start=period_start, period_end=period_end,
            amount=amount,
            # 快照记录当时的计提依据；日后改率也解释得清这笔钱是怎么来的。
            amount_snapshot_json=json.dumps(accrual, ensure_ascii=False, default=str),
            status="draft", created_at=business_now())
        session.add(item)
        total += amount

    statement.total_amount = round(total, 2)
    session.flush()
    return statement


def confirm_statement(session: Session, admin: User, statement_id: int) -> AgentCommissionStatement:
    _require_admin(admin)
    statement = session.get(AgentCommissionStatement, statement_id)
    if not statement:
        raise HTTPException(404, "结算单不存在")
    if statement.status != "draft":
        raise HTTPException(409, "只能确认草稿状态的结算单")

    for item in session.scalars(select(AgentCommissionStatementItem).where(
            AgentCommissionStatementItem.statement_id == statement.id,
            AgentCommissionStatementItem.status == "draft")):
        item.status = "confirmed"
    statement.status = "confirmed"
    statement.confirmed_at = business_now()
    session.flush()
    return statement


def adjust_item(session: Session, admin: User, item_id: int, *, amount: float,
                reason: str) -> AgentCommissionStatementItem:
    """Correct a confirmed item by appending, never by rewriting (§5.3).

    There is deliberately no in-place path: rewriting a confirmed amount
    destroys the evidence of what was agreed and makes a dispute unresolvable.
    """
    _require_admin(admin)
    if not (reason or "").strip():
        raise HTTPException(400, "调整必须填写原因")
    original = session.get(AgentCommissionStatementItem, item_id)
    if not original:
        raise HTTPException(404, "结算项不存在")
    if original.status == "void":
        raise HTTPException(409, "已作废结算项不能再调整")

    adjustment = AgentCommissionStatementItem(
        statement_id=original.statement_id,
        source_type="reversal" if amount == -original.amount else "adjustment",
        plan_id=original.plan_id, enterprise_id=original.enterprise_id,
        period_start=original.period_start, period_end=original.period_end,
        amount=round(float(amount), 2),
        amount_snapshot_json=json.dumps(
            {"adjusts_item_id": original.id, "reason": reason,
             "original_amount": original.amount}, ensure_ascii=False),
        status="confirmed", adjusts_item_id=original.id, created_at=business_now())
    session.add(adjustment)

    statement = session.get(AgentCommissionStatement, original.statement_id)
    if statement:
        statement.total_amount = round(statement.total_amount + adjustment.amount, 2)
    session.flush()
    return adjustment


def record_payment(session: Session, admin: User, *, agent_id: int, amount: float,
                   channel: str, transaction_no: str, paid_at: datetime,
                   voucher_url: str = "") -> AgentCommissionPayment:
    _require_admin(admin)
    if float(amount) <= 0:
        raise HTTPException(400, "付款金额必须大于 0")
    payment = AgentCommissionPayment(
        agent_id=agent_id, amount=round(float(amount), 2), channel=channel,
        transaction_no=transaction_no, paid_at=paid_at, voucher_url=voucher_url,
        created_by=admin.id, created_at=business_now())
    session.add(payment)
    session.flush()
    return payment


def _allocated_of_payment(session: Session, payment_id: int) -> float:
    return float(session.scalar(
        select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0))
        .where(AgentCommissionPaymentAllocation.payment_id == payment_id)) or 0)


def _allocated_to_statement(session: Session, statement_id: int) -> float:
    return float(session.scalar(
        select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0))
        .where(AgentCommissionPaymentAllocation.statement_id == statement_id)) or 0)


def allocate(session: Session, admin: User, *, payment_id: int, statement_id: int,
             amount: float) -> AgentCommissionPaymentAllocation:
    """分配金额不得超过付款可分配余额或结算单未付余额（§5.3）。

    Both ceilings span tables, so no CHECK can hold them. They are checked here
    with the payment and statement rows locked, in one transaction: without the
    lock two concurrent allocations both read the old balance and together
    overpay. `with_for_update` is a no-op on SQLite but real on PostgreSQL,
    which is where the money lives.
    """
    _require_admin(admin)
    value = round(float(amount), 2)
    if value <= 0:
        raise HTTPException(400, "分配金额必须大于 0")

    payment = session.scalar(
        select(AgentCommissionPayment)
        .where(AgentCommissionPayment.id == payment_id).with_for_update())
    if not payment:
        raise HTTPException(404, "付款记录不存在")
    statement = session.scalar(
        select(AgentCommissionStatement)
        .where(AgentCommissionStatement.id == statement_id).with_for_update())
    if not statement:
        raise HTTPException(404, "结算单不存在")
    if payment.agent_id != statement.agent_id:
        raise HTTPException(400, "付款与结算单不属于同一业务员")
    if statement.status == "void":
        raise HTTPException(409, "已作废结算单不能分配付款")

    payment_left = round(payment.amount - _allocated_of_payment(session, payment.id), 2)
    if value > payment_left:
        raise HTTPException(400, f"分配金额超过该付款可分配余额（剩余 {payment_left}）")

    statement_left = round(
        statement.total_amount - _allocated_to_statement(session, statement.id), 2)
    if value > statement_left:
        raise HTTPException(400, f"分配金额超过该结算单未付余额（剩余 {statement_left}）")

    allocation = AgentCommissionPaymentAllocation(
        payment_id=payment.id, statement_id=statement.id, amount=value,
        created_at=business_now())
    session.add(allocation)
    session.flush()

    paid = _allocated_to_statement(session, statement.id)
    if paid >= statement.total_amount:
        statement.status = "paid"
    elif paid > 0:
        statement.status = "partially_paid"
    session.flush()
    return allocation


def agent_balances(session: Session, agent_id: int) -> dict:
    """§5.2 预估累计 / 待结算 / 待支付 / 已支付。

    `paid` counts allocations, not payments: an unallocated payment is money in
    the account, not money attributed to a statement.
    """
    statements = list(session.scalars(
        select(AgentCommissionStatement).where(
            AgentCommissionStatement.agent_id == agent_id,
            AgentCommissionStatement.status != "void")))
    statement_ids = [s.id for s in statements]

    paid = 0.0
    if statement_ids:
        paid = float(session.scalar(
            select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0))
            .where(AgentCommissionPaymentAllocation.statement_id.in_(statement_ids))) or 0)

    confirmed_total = sum(s.total_amount for s in statements
                          if s.status in ("confirmed", "partially_paid", "paid"))
    draft_total = sum(s.total_amount for s in statements if s.status == "draft")

    # 预估累计：尚未开单的计提 + 已开单金额。业务员看的是"我一共该拿多少"。
    estimated = float(agent_commission_summary(session, agent_id).get("estimated", 0) or 0)

    return {
        "estimated_total": round(max(estimated, confirmed_total + draft_total), 2),
        "pending_settlement": round(draft_total, 2),
        "pending_payment": round(confirmed_total - paid, 2),
        "paid": round(paid, 2),
    }
