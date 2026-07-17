"""Schema-level guarantees for the settlement ledger (v4.2 §5.3).

These are the rules a database can keep on its own — status vocabularies,
positive amounts, a unique transaction number per channel. The cross-table
balance ceilings live in the service (agent_settlement_service_test.py); a CHECK
cannot span tables.
"""
import os
import sys
from datetime import date, datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "settlement-model-test")

from sqlalchemy import create_engine, event
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    AgentCommissionPayment,
    AgentCommissionStatement,
    AgentCommissionStatementItem,
    Enterprise,
    User,
)


def _now():
    return datetime.now(timezone.utc)


def _engine():
    engine = create_engine("sqlite:///:memory:")

    @event.listens_for(engine, "connect")
    def _fk(conn, _rec):
        conn.execute("PRAGMA foreign_keys=ON")

    Base.metadata.create_all(engine)
    return engine


def _agent(session):
    agent = User(username=f"m_agent{id(session)%10000}", password_hash="x",
                 name="业务员", role="salesperson")
    session.add(agent)
    session.flush()
    return agent


def run() -> None:
    engine = _engine()
    _test_statement_status_constrained(engine)
    _test_statement_period_ordered(engine)
    _test_item_source_and_status_constrained(engine)
    _test_payment_amount_must_be_positive(engine)
    _test_duplicate_channel_txn_rejected(engine)

    print("agent settlement model tests passed")


def _statement(session, agent, **kwargs):
    values = dict(agent_id=agent.id, statement_no=f"S{id(kwargs)%100000}",
                  period_start=date(2026, 3, 1), period_end=date(2026, 3, 31),
                  status="draft", created_at=_now())
    values.update(kwargs)
    return AgentCommissionStatement(**values)


def _test_statement_status_constrained(engine):
    with Session(engine) as session:
        agent = _agent(session)
        session.add(_statement(session, agent, status="nonsense"))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("未知结算单状态必须被拒")
    print("  statement status constrained ok")


def _test_statement_period_ordered(engine):
    with Session(engine) as session:
        agent = _agent(session)
        session.add(_statement(session, agent, period_start=date(2026, 3, 31),
                               period_end=date(2026, 3, 1)))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("结算期间结束早于开始必须被拒")
    print("  statement period ordered ok")


def _test_item_source_and_status_constrained(engine):
    with Session(engine) as session:
        agent = _agent(session)
        st = _statement(session, agent)
        session.add(st)
        session.flush()
        session.add(AgentCommissionStatementItem(
            statement_id=st.id, source_type="not_a_source", amount=10,
            status="draft", created_at=_now()))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("未知结算项来源必须被拒")
    print("  item source constrained ok")


def _test_payment_amount_must_be_positive(engine):
    with Session(engine) as session:
        agent = _agent(session)
        session.add(AgentCommissionPayment(
            agent_id=agent.id, amount=0, channel="bank", transaction_no="",
            paid_at=_now(), created_at=_now()))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("付款金额必须大于 0")
    print("  payment amount positive ok")


def _test_duplicate_channel_txn_rejected(engine):
    with Session(engine) as session:
        agent = _agent(session)
        for _ in range(2):
            session.add(AgentCommissionPayment(
                agent_id=agent.id, amount=50, channel="bank", transaction_no="TXN-9",
                paid_at=_now(), created_at=_now()))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("同一渠道流水号不得重复")

    # 空流水号不参与唯一性（部分索引）
    with Session(engine) as session:
        agent = _agent(session)
        for _ in range(2):
            session.add(AgentCommissionPayment(
                agent_id=agent.id, amount=50, channel="bank", transaction_no="",
                paid_at=_now(), created_at=_now()))
        session.commit()
    print("  duplicate channel txn rejected ok")


if __name__ == "__main__":
    run()
