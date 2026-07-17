"""Agent settlement, payment and allocation (v4.2 §5.2, §5.3).

This is money. The rules that matter:

- 已确认结算项不得原地改写，差错通过调整项或冲正记录处理 (§5.3). A confirmed
  amount is evidence of what was agreed; rewriting it destroys the audit trail
  and lets a dispute become unresolvable.
- 分配金额不得超过付款可分配余额或结算单未付余额 (§5.3). These ceilings span
  tables, so no single CHECK can hold them — they live in the service inside one
  transaction. An over-allocation means paying money twice.
- 结算项固化金额快照 (§5.3): changing a commission rate today must not silently
  restate what was already settled.
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
from backend.services.agent_settlements import (
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
    ctx.admin = User(username=f"st_admin{ctx.seq}", password_hash="x", name="平台", role="admin")
    ctx.agent = User(username=f"st_agent{ctx.seq}", password_hash="x", name="业务员",
                     role="salesperson")
    session.add_all([ctx.admin, ctx.agent])
    session.flush()
    ctx.enterprise = Enterprise(name=f"结算企业{ctx.seq}")
    session.add(ctx.enterprise)
    session.flush()
    ctx.plan = InsurancePlan(name="结算产品", insurer="保司", price=100,
                             commission_rate=0.3, profit_amount=10, status="active")
    session.add(ctx.plan)
    session.flush()
    ctx.relation = AgentCommission(
        agent_id=ctx.agent.id, enterprise_id=ctx.enterprise.id, plan_id=ctx.plan.id,
        rate=0.1, mode="rebate", status="active")
    session.add(ctx.relation)
    session.flush()
    return ctx


def _statement(session, ctx, *, total, status="confirmed"):
    st = AgentCommissionStatement(
        agent_id=ctx.agent.id, statement_no=f"ST-{ctx.seq}-{next(_SEQ)}",
        period_start=P1, period_end=P2, total_amount=total, status=status,
        created_at=datetime.now(timezone.utc))
    session.add(st)
    session.flush()
    return st


def _payment(session, ctx, *, amount):
    return record_payment(session, ctx.admin, agent_id=ctx.agent.id, amount=amount,
                          channel="bank", transaction_no=f"TXN-{next(_SEQ)}",
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
    _test_confirm_freezes_the_amount_snapshot(engine)
    _test_balances_split_the_four_states(engine)
    _test_paid_balance_counts_allocations_not_payments(engine)

    print("agent settlement service tests passed")


def _test_allocation_cannot_exceed_payment_balance(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        pay = _payment(session, ctx, amount=100)
        s1, s2 = _statement(session, ctx, total=80), _statement(session, ctx, total=80)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=s1.id, amount=80)
        try:
            allocate(session, ctx.admin, payment_id=pay.id, statement_id=s2.id, amount=30)
        except HTTPException as exc:
            assert exc.status_code == 400, exc.status_code
        else:
            raise AssertionError("分配额超过付款可分配余额必须被拒（只剩 20）")
    print("  payment balance ceiling ok")


def _test_allocation_cannot_exceed_statement_unpaid_balance(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=50)
        pay = _payment(session, ctx, amount=100)
        try:
            allocate(session, ctx.admin, payment_id=pay.id, statement_id=st.id, amount=60)
        except HTTPException as exc:
            assert exc.status_code == 400, exc.status_code
        else:
            raise AssertionError("分配额超过结算单未付余额必须被拒")
    print("  statement balance ceiling ok")


def _test_one_statement_can_be_paid_in_instalments(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100)
        allocate(session, ctx.admin, payment_id=_payment(session, ctx, amount=60).id,
                 statement_id=st.id, amount=60)
        assert st.status == "partially_paid", st.status
        allocate(session, ctx.admin, payment_id=_payment(session, ctx, amount=40).id,
                 statement_id=st.id, amount=40)
        assert st.status == "paid", st.status
    print("  instalments ok")


def _test_one_payment_can_clear_several_statements(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        pay = _payment(session, ctx, amount=100)
        allocate(session, ctx.admin, payment_id=pay.id,
                 statement_id=_statement(session, ctx, total=60).id, amount=60)
        allocate(session, ctx.admin, payment_id=pay.id,
                 statement_id=_statement(session, ctx, total=40).id, amount=40)
        assert agent_balances(session, ctx.agent.id)["paid"] == 100
    print("  one payment many statements ok")


def _test_confirmed_item_cannot_be_rewritten_in_place(engine):
    """§5.3 已确认结算项不得原地改写。"""
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100, status="draft")
        item = AgentCommissionStatementItem(
            statement_id=st.id, amount=100, status="confirmed", source_type="accrual",
            created_at=datetime.now(timezone.utc))
        session.add(item)
        session.flush()
        try:
            adjust_item(session, ctx.admin, item.id, amount=120, reason="改金额", in_place=True)
        except HTTPException as exc:
            assert exc.status_code == 409, exc.status_code
        except TypeError:
            pass   # in_place 参数不存在即说明原地改写根本无法表达，更好
        else:
            raise AssertionError("已确认结算项不得原地改写")
        assert item.amount == 100
    print("  confirmed item immutable ok")


def _test_adjustment_creates_a_new_row_pointing_at_the_original(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=100, status="draft")
        item = AgentCommissionStatementItem(
            statement_id=st.id, amount=100, status="confirmed", source_type="accrual",
            created_at=datetime.now(timezone.utc))
        session.add(item)
        session.flush()

        adj = adjust_item(session, ctx.admin, item.id, amount=-20, reason="多算")
        assert adj.id != item.id
        assert adj.adjusts_item_id == item.id
        assert adj.source_type == "adjustment"
        assert adj.amount == -20
        assert item.amount == 100, "原项必须保持不变"
    print("  adjustment appends ok")


def _test_confirm_freezes_the_amount_snapshot(engine):
    """确认后改佣金比例，已结算金额不得随之变化。"""
    with Session(engine) as session:
        ctx = _setup(session)
        st = build_statement(session, ctx.admin, agent_id=ctx.agent.id,
                             period_start=P1, period_end=P2)
        confirm_statement(session, ctx.admin, st.id)
        frozen = st.total_amount

        ctx.relation.rate = 0.99      # 事后改佣金关系
        session.flush()

        session.refresh(st)
        assert st.total_amount == frozen, "已确认结算单金额不得被事后改率影响"
        for item in session.scalars(select(AgentCommissionStatementItem).where(
                AgentCommissionStatementItem.statement_id == st.id)):
            assert item.amount_snapshot_json, "结算项必须固化金额快照"
    print("  snapshot frozen ok")


def _test_balances_split_the_four_states(engine):
    """§5.2 预估累计 / 待结算 / 待支付 / 已支付。"""
    with Session(engine) as session:
        ctx = _setup(session)
        b = agent_balances(session, ctx.agent.id)
        assert set(b) >= {"estimated_total", "pending_settlement", "pending_payment", "paid"}
    print("  four balance states ok")


def _test_paid_balance_counts_allocations_not_payments(engine):
    """一次付款覆盖多张结算单时，已支付按分配额计，而非付款总额。"""
    with Session(engine) as session:
        ctx = _setup(session)
        st = _statement(session, ctx, total=60)
        pay = _payment(session, ctx, amount=100)
        allocate(session, ctx.admin, payment_id=pay.id, statement_id=st.id, amount=60)
        assert agent_balances(session, ctx.agent.id)["paid"] == 60, "未分配的 40 不算已支付"
    print("  paid counts allocations ok")


if __name__ == "__main__":
    run()
