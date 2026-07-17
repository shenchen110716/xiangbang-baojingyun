"""Agent commission settlement, payment and allocation (v4.2 §5.2, §5.3).

An append-only money ledger. Confirmed amounts are never rewritten — a
correction is a new adjustment or reversal row pointing at the one it fixes, so
the audit trail survives a dispute. Allocation ceilings span tables (a payment's
remaining balance and a statement's unpaid balance), so they cannot be a single
CHECK; they are enforced here inside one transaction with row locks, because an
over-allocation pays money out twice.

The portal read helpers (`portal_products`, `portal_commission_summary`) live
here too so the leakage contract has a single home: the product view is an
explicit allow-list, and an agent's identity comes only from the caller, never
from a query parameter.
"""
import json
from datetime import date, datetime, timezone
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from ..models import (
    AgentCommission,
    AgentCommissionPayment,
    AgentCommissionPaymentAllocation,
    AgentCommissionStatement,
    AgentCommissionStatementItem,
    InsurancePlan,
    User,
)
from .commissions import commission_accrual
from .pricing import pricing_snapshot

# ---- 结算账本 -------------------------------------------------------------


def _require_admin(user: User) -> None:
    if user.role != "admin":
        raise HTTPException(403, "仅平台管理员可管理佣金结算")


def _statement_no(session: Session, agent_id: int) -> str:
    seq = session.scalar(select(func.count(AgentCommissionStatement.id))) or 0
    return f"AS{datetime.now(timezone.utc):%Y%m}{agent_id:04d}{seq + 1:04d}"


def build_statement(session: Session, admin: User, *, agent_id: int,
                    period_start: date, period_end: date) -> AgentCommissionStatement:
    """Draft a statement from the agent's accruals over the period."""
    _require_admin(admin)
    if period_end < period_start:
        raise HTTPException(400, "结算期间的结束日期不能早于开始日期")

    statement = AgentCommissionStatement(
        agent_id=agent_id, statement_no=_statement_no(session, agent_id),
        period_start=period_start, period_end=period_end, currency="CNY",
        total_amount=0, status="draft", created_at=datetime.now(timezone.utc))
    session.add(statement)
    session.flush()

    total = 0.0
    relations = session.scalars(select(AgentCommission).where(
        AgentCommission.agent_id == agent_id, AgentCommission.status == "active"))
    for relation in relations:
        accrual = commission_accrual(session, relation, as_of=period_end)
        amount = round(float(accrual.get("accrued_agent_commission") or 0), 2)
        if amount <= 0:
            continue
        # 金额快照固化在结算项里；日后改产品或佣金关系都不得改写它（§5.3）。
        session.add(AgentCommissionStatementItem(
            statement_id=statement.id, source_type="accrual",
            plan_id=relation.plan_id, enterprise_id=relation.enterprise_id,
            period_start=period_start, period_end=period_end,
            amount=amount, amount_snapshot_json=json.dumps(accrual, ensure_ascii=False),
            status="draft", created_at=datetime.now(timezone.utc)))
        total += amount

    statement.total_amount = round(total, 2)
    session.flush()
    return statement


def confirm_statement(session: Session, admin: User,
                      statement_id: int) -> AgentCommissionStatement:
    """Freeze the draft items. After this, amounts change only via adjustments."""
    _require_admin(admin)
    statement = session.get(AgentCommissionStatement, statement_id)
    if not statement:
        raise HTTPException(404, "结算单不存在")
    if statement.status != "draft":
        raise HTTPException(409, "只有草稿结算单可以确认")
    session.execute(
        update(AgentCommissionStatementItem)
        .where(AgentCommissionStatementItem.statement_id == statement_id,
               AgentCommissionStatementItem.status == "draft")
        .values(status="confirmed"))
    statement.status = "confirmed"
    statement.confirmed_at = datetime.now(timezone.utc)
    _refresh_total(session, statement)
    session.flush()
    return statement


def adjust_item(session: Session, admin: User, item_id: int, *,
                amount: float, reason: str) -> AgentCommissionStatementItem:
    """Correct a confirmed item by appending an adjustment (§5.3).

    Never rewrites the original: `amount` is the delta, recorded as a new row
    that points back at the item it adjusts. The original stays as evidence of
    what was first agreed.
    """
    _require_admin(admin)
    original = session.get(AgentCommissionStatementItem, item_id)
    if not original:
        raise HTTPException(404, "结算项不存在")
    if not (reason or "").strip():
        raise HTTPException(400, "调整必须填写原因")

    adjustment = AgentCommissionStatementItem(
        statement_id=original.statement_id,
        source_type="adjustment" if amount != 0 else "reversal",
        plan_id=original.plan_id, enterprise_id=original.enterprise_id,
        period_start=original.period_start, period_end=original.period_end,
        amount=round(float(amount), 2),
        amount_snapshot_json=json.dumps({"reason": reason, "adjusts": item_id},
                                        ensure_ascii=False),
        status="confirmed", adjusts_item_id=item_id,
        created_at=datetime.now(timezone.utc))
    session.add(adjustment)
    statement = session.get(AgentCommissionStatement, original.statement_id)
    session.flush()
    _refresh_total(session, statement)
    session.flush()
    return adjustment


def _refresh_total(session: Session, statement: AgentCommissionStatement) -> None:
    total = session.scalar(
        select(func.coalesce(func.sum(AgentCommissionStatementItem.amount), 0.0))
        .where(AgentCommissionStatementItem.statement_id == statement.id,
               AgentCommissionStatementItem.status != "void")) or 0.0
    statement.total_amount = round(float(total), 2)


def record_payment(session: Session, admin: User, *, agent_id: int, amount: float,
                   channel: str, transaction_no: str, paid_at: datetime,
                   voucher_url: str = "") -> AgentCommissionPayment:
    _require_admin(admin)
    if amount <= 0:
        raise HTTPException(400, "付款金额必须大于 0")
    payment = AgentCommissionPayment(
        agent_id=agent_id, amount=round(float(amount), 2), channel=channel,
        transaction_no=transaction_no, paid_at=paid_at, voucher_url=voucher_url,
        created_by=admin.id, created_at=datetime.now(timezone.utc))
    session.add(payment)
    session.flush()
    return payment


def _payment_allocated(session: Session, payment_id: int) -> float:
    return float(session.scalar(
        select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0.0))
        .where(AgentCommissionPaymentAllocation.payment_id == payment_id)) or 0.0)


def _statement_allocated(session: Session, statement_id: int) -> float:
    return float(session.scalar(
        select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0.0))
        .where(AgentCommissionPaymentAllocation.statement_id == statement_id)) or 0.0)


def allocate(session: Session, admin: User, *, payment_id: int, statement_id: int,
             amount: float) -> AgentCommissionPaymentAllocation:
    """Allocate part of a payment to a statement, enforcing both ceilings (§5.3).

    The ceilings cannot be a single cross-table CHECK, so they are checked here
    after locking the payment and statement rows. Locking first is what makes it
    safe under concurrency: without it, two allocations could each read "enough
    remaining" and both succeed, paying the money out twice.
    """
    _require_admin(admin)
    if amount <= 0:
        raise HTTPException(400, "分配金额必须大于 0")

    payment = _lock_row(session, AgentCommissionPayment, payment_id)
    statement = _lock_row(session, AgentCommissionStatement, statement_id)
    if not payment or not statement:
        raise HTTPException(404, "付款或结算单不存在")
    if payment.agent_id != statement.agent_id:
        raise HTTPException(400, "付款与结算单不属于同一业务员")
    if statement.status not in ("confirmed", "partially_paid"):
        raise HTTPException(409, "只有已确认或部分支付的结算单可以分配付款")

    amount = round(float(amount), 2)
    payment_remaining = round(payment.amount - _payment_allocated(session, payment_id), 2)
    if amount > payment_remaining:
        raise HTTPException(400, f"超过付款可分配余额（剩余 {payment_remaining}）")
    statement_unpaid = round(statement.total_amount - _statement_allocated(session, statement_id), 2)
    if amount > statement_unpaid:
        raise HTTPException(400, f"超过结算单未付余额（剩余 {statement_unpaid}）")

    allocation = AgentCommissionPaymentAllocation(
        payment_id=payment_id, statement_id=statement_id, amount=amount,
        created_at=datetime.now(timezone.utc))
    session.add(allocation)
    session.flush()

    paid = _statement_allocated(session, statement_id)
    statement.status = "paid" if round(paid, 2) >= statement.total_amount else "partially_paid"
    session.flush()
    return allocation


def _lock_row(session: Session, model, row_id: int):
    """Pessimistic row lock where the dialect supports it (PostgreSQL). SQLite
    serializes writes anyway, so with_for_update degrades to a plain get there."""
    stmt = select(model).where(model.id == row_id)
    try:
        return session.scalars(stmt.with_for_update()).first()
    except Exception:
        return session.get(model, row_id)


def agent_balances(session: Session, agent_id: int) -> dict:
    """§5.2 预估累计 / 待结算 / 待支付 / 已支付。

    Paid counts allocations, not payments: one payment can clear several
    statements, so the money actually attributed to this agent's statements is
    the sum of allocations.
    """
    estimated = 0.0
    for relation in session.scalars(select(AgentCommission).where(
            AgentCommission.agent_id == agent_id, AgentCommission.status == "active")):
        accrual = commission_accrual(session, relation)
        estimated += float(accrual.get("accrued_agent_commission") or 0)

    statements = list(session.scalars(select(AgentCommissionStatement).where(
        AgentCommissionStatement.agent_id == agent_id,
        AgentCommissionStatement.status != "void")))
    statement_ids = [s.id for s in statements]

    confirmed_total = sum(s.total_amount for s in statements
                          if s.status in ("confirmed", "partially_paid", "paid"))
    paid = 0.0
    if statement_ids:
        paid = float(session.scalar(
            select(func.coalesce(func.sum(AgentCommissionPaymentAllocation.amount), 0.0))
            .where(AgentCommissionPaymentAllocation.statement_id.in_(statement_ids))) or 0.0)

    return {
        "agent_id": agent_id,
        "estimated_total": round(estimated, 2),
        # 已计提但尚未开结算单的部分。
        "pending_settlement": round(max(0.0, estimated - confirmed_total), 2),
        # 已确认结算单里尚未收到付款的部分。
        "pending_payment": round(confirmed_total - paid, 2),
        "paid": round(paid, 2),
    }


# ---- 门户只读（泄漏契约的唯一实现处）-------------------------------------

# §5.1 白名单：业务员产品响应只允许这些字段，多一个就算泄漏。
PORTAL_PRODUCT_FIELDS = frozenset({
    "id", "insurer", "name", "coverage", "occupation_classes",
    "billing_mode", "effective_mode", "status",
    "min_sale_price", "my_commission_status",
})


def portal_products(session: Session, agent: User) -> list[dict]:
    """Every sellable product with the platform minimum price, built as an
    allow-list so a column added to InsurancePlan cannot leak here by default.
    Internal cost basis (settlement price, profit, rebate) never leaves this
    function (§5.1)."""
    configured = {
        relation.plan_id
        for relation in session.scalars(select(AgentCommission).where(
            AgentCommission.agent_id == agent.id))
    }
    products = []
    for plan in session.scalars(select(InsurancePlan).where(
            InsurancePlan.status == "active").order_by(InsurancePlan.id)):
        # min_sale_price is computed server-side; the raw cost figures in the
        # snapshot are dropped.
        snapshot = pricing_snapshot(plan)
        products.append({
            "id": plan.id,
            "insurer": plan.insurer,
            "name": plan.name,
            "coverage": plan.coverage,
            "occupation_classes": plan.occupation_classes,
            "billing_mode": plan.billing_mode,
            "effective_mode": plan.effective_mode,
            "status": plan.status,
            "min_sale_price": snapshot["minimum_sale_price"],
            "my_commission_status": "已配置" if plan.id in configured else "未配置",
        })
    return products


def portal_commission_summary(session: Session, agent: User, *,
                              agent_id: Optional[int] = None) -> dict:
    """The caller's own balances only. `agent_id` is accepted and ignored: an
    identity a query parameter can move is one somebody forgets to check
    (§17.1). Identity is the authenticated agent, full stop."""
    return agent_balances(session, agent.id)
