"""Agent settlement ledger service (v4.2 §5.2, §5.3).

This is money. The rules that matter:

- 已确认结算项不得原地改写，差错通过调整项或冲正记录处理 (§5.3). A confirmed
  amount is evidence of what was agreed; rewriting it destroys the audit trail
  and lets a dispute become unresolvable.
- 分配金额不得超过付款可分配余额或结算单未付余额 (§5.3). These ceilings span
  tables, so no single CHECK can hold them — the service must, inside one
  transaction. An over-allocation means paying out money twice.
- 结算项固化金额快照 (§5.3): changing a commission rate later must not silently
  restate a settled period.
"""
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "settlement-test")

from fastapi import HTTPException
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    AgentCommission,
    AgentCommissionStatement,
    AgentCommissionStatementItem,
    Enterprise,
    InsurancePlan,
    User,
)
from backend.services.agent_settlement import (
    adjust_item,
    agent_balances,
    allocate,
    build_statement,
    confirm_statement,
    record_payment,
)

P1, P2 = date(2026, 3, 1), date(2026, 3, 31)


class _Ctx:
    pass


_SEQ = iter(range(1, 10_000))


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.seq = next(_SEQ)
    ctx.enterprise = Enterprise(name=f"结算企业{ctx.seq}")
    session.add(ctx.enterprise)
    session.flush()
    ctx.admin = User(username=f"st_admin{ctx.seq}", password_hash="x", name="平台", role="admin")
    ctx.agent = User(username=f"st_agent{ctx.seq}", password_hash="x", name="业务员",
                     role="salesperson")
    session.add_all([ctx.admin, ctx.agent])
    session.flush()
    ctx.plan = InsurancePlan(name=f"产品{ctx.seq}", insurer="保司", price=100,
                             commission_rate=0.3, profit_amount=10, status="active")
    session.add(ctx.plan)
    session.flush()
    ctx.relation = AgentCommission(agent_id=ctx.agent.id, enterprise_id=ctx.enterprise.id,
                                   plan_id=ctx.plan.id, rate=0.1, mode="rebate",
                                   status="active")
    session.add(ctx.relation)
    session.flush()
    return ctx


def _statement(session, ctx, *, total=100.0, status="confirmed"):
    st = build_statement(session, ctx.admin, agent_id=ctx.agent.id,
                         period_start=P1, period_end=P2)
    # 计提为 0 时直接给一条项目，聚焦本测试要验的分配规则。
    session.add(AgentCommissionStatementItem(
        statement_id=st.id, source_type="accrual", amount=total,
        enterprise_id=ctx.enterprise.id, plan_id=ctx.plan.id,
        status="draft", created_at=datetime.now(timezone.utc)))
    session.flush()
    st.total_amount = total
    session.flush()
    if status == "confirmed":
        confirm_statement(session, ctx.admin, st.id)
    return st


def _payment(session, ctx, amount, txn=""):
    return record_payment(session, ctx.admin, agent_id=ctx.agent.id, amount=amount,
                          channel="bank", transaction_no=txn,
                          paid_at=datetime.now(timezone.utc), voucher_url="")


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    _test_allocation_cannot_exceed_payment_balance(engine)
    _test_allocation_cannot_exceed_statement_unpaid_balance(engine)
    _test_one_statement_can_be_paid_in_instalments(engine)
    _test_one_payment_can_clear_several_statements(engine)
    _test_confirmed_item_cannot_be_rewritten_in_place(engine)
    _test_adjustment_creates_a_new_row_pointing_at_the_original(engine)
    _test_balances_split_the_four_states(engine)
    _test_paid_balance_counts_allocations_not_payments(engine)
    _test_duplicate_transaction_no_is_rejected(engine)

    print("agent settlement service tests passed")


def _test_allocation_cannot_exceed_payment_balance(engine):
    """§5.3 分配金额不得超过付款可分配余额——超额等于把钱付两遍。"""
    with Session(engine) as session:
        ctx = _setup(session)
        pay = _payment(session, ctx, 100)
        s1 = _statement(session, ctx, total=80)
        s2 = _statement(session, ctx, total=80)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=s1.id, amount=80)
        try:
            allocate(session, ctx.admin, payment_id=pay.id, statement_id=s2.id, amount=30)
        except HTTPException as exc:
            assert exc.status_code == 400, exc.status_code
        else:
            raise AssertionError("付款只剩 20，不得再分配 30")
    print("  payment balance ceiling ok")


def _test_allocation_cannot_exceed_statement_unpaid_balance(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=50)
        pay = _payment(session, ctx, 100)
        try:
            allocate(session, ctx.admin, payment_id=pay.id, statement_id=st.id, amount=60)
        except HTTPException as exc:
            assert exc.status_code == 400, exc.status_code
        else:
            raise AssertionError("结算单只欠 50，不得分配 60")
    print("  statement balance ceiling ok")


def _test_one_statement_can_be_paid_in_instalments(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100)
        allocate(session, ctx.admin, payment_id=_payment(session, ctx, 60).id,
                 statement_id=st.id, amount=60)
        assert st.status == "partially_paid", st.status
        allocate(session, ctx.admin, payment_id=_payment(session, ctx, 40).id,
                 statement_id=st.id, amount=40)
        assert st.status == "paid", st.status
    print("  instalments ok")


def _test_one_payment_can_clear_several_statements(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        pay = _payment(session, ctx, 100)
        a = _statement(session, ctx, total=60)
        b = _statement(session, ctx, total=40)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=a.id, amount=60)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=b.id, amount=40)
        assert a.status == "paid" and b.status == "paid"
    print("  one payment many statements ok")


def _test_confirmed_item_cannot_be_rewritten_in_place(engine):
    """§5.3 已确认结算项不得原地改写。"""
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100)
        item = session.scalar(select(AgentCommissionStatementItem).where(
            AgentCommissionStatementItem.statement_id == st.id))
        assert item.status == "confirmed"
        try:
            adjust_item(session, ctx.admin, item.id, amount=120, reason="改金额", in_place=True)
        except (HTTPException, TypeError) as exc:
            if isinstance(exc, HTTPException):
                assert exc.status_code == 409, exc.status_code
        else:
            raise AssertionError("已确认结算项不得原地改写")
    print("  no in-place rewrite ok")


def _test_adjustment_creates_a_new_row_pointing_at_the_original(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100)
        item = session.scalar(select(AgentCommissionStatementItem).where(
            AgentCommissionStatementItem.statement_id == st.id))

        adj = adjust_item(session, ctx.admin, item.id, amount=-20, reason="多算")
        assert adj.id != item.id
        assert adj.adjusts_item_id == item.id
        assert adj.source_type == "adjustment"
        assert item.amount == 100, "原项金额必须保持不变"
        # 结算单总额随调整项更新
        assert st.total_amount == 80, st.total_amount
    print("  adjustment appends ok")


def _test_balances_split_the_four_states(engine):
    """§5.2 预估累计 / 待结算 / 待支付 / 已支付。"""
    with Session(engine) as session:
        ctx = _setup(session)
        b = agent_balances(session, ctx.agent.id)
        assert set(b) >= {"estimated_total", "pending_settlement", "pending_payment", "paid"}, b
    print("  four balance states ok")


def _test_paid_balance_counts_allocations_not_payments(engine):
    """一次付款覆盖多张结算单时，已支付按分配额计，而非付款额。"""
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=60)
        pay = _payment(session, ctx, 100)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=st.id, amount=60)
        session.flush()
        b = agent_balances(session, ctx.agent.id)
        assert b["paid"] == 60, f"已支付应按分配额 60 计，而非付款额 100，实为 {b['paid']}"
    print("  paid counts allocations ok")


def _test_duplicate_transaction_no_is_rejected(engine):
    """同一渠道流水号重复入账 = 一次付款记两遍。"""
    with Session(engine) as session:
        ctx = _setup(session)
        _payment(session, ctx, 50, txn="TXN-1")
        session.flush()
        try:
            _payment(session, ctx, 50, txn="TXN-1")
            session.flush()
        except Exception:
            session.rollback()
        else:
            raise AssertionError("同一渠道流水号不得重复入账")
    print("  duplicate txn rejected ok")


if __name__ == "__main__":
    run()
