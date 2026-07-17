"""Every change that invalidates a timeliness verdict must enqueue a recalc.

The Outbox existed but nothing populated it: `enqueue` was called from exactly
one place — the manual recalculate endpoint, which enqueues everything and then
drains it synchronously. So the queue was decorative, and the consequences were
not merely stale numbers:

- correcting a fact left the *superseded* fact's verdict marked `current` while
  the new revision had no verdict at all, so reports kept showing a judgement
  the user had already corrected, with nothing to indicate it was stale;
- importing facts produced no verdicts until somebody happened to press
  recalculate;
- changing coverage (a stop/start) never re-judged the affected facts, even
  though the verdict is literally a comparison against coverage.

These tests pin the enqueue points and the drain.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "outbox-wiring-test")

from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    ActualEmployer,
    EmploymentFact,
    EmploymentTimelinessResult,
    Enterprise,
    TimelinessOutbox,
    User,
)
from backend.services.employment_facts import correct_fact
from backend.services.timeliness_recalc import drain_due, process_outbox, recalculate


def D(*args):
    return datetime(*args, tzinfo=timezone.utc)


class _Ctx:
    pass


_SEQ = iter(range(1, 10_000))


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.seq = next(_SEQ)
    ctx.enterprise = Enterprise(name=f"队列企业{ctx.seq}")
    session.add(ctx.enterprise)
    session.flush()
    ctx.employer = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    session.add(ctx.employer)
    session.flush()
    ctx.owner = User(username=f"ob_owner{ctx.seq}", password_hash="x", name="主管",
                     role="enterprise", enterprise_id=ctx.enterprise.id,
                     enterprise_role="owner", is_owner=True)
    session.add(ctx.owner)
    session.flush()
    return ctx


def _fact(session, ctx, *, hire=D(2026, 3, 1), revision=1):
    f = EmploymentFact(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer.id,
        person_name="张三", actual_hire_at=hire, status="active",
        revision_no=revision, created_at=D(2026, 3, 1))
    session.add(f)
    session.flush()
    return f


def _live_outbox(session, fact_id):
    return list(session.scalars(select(TimelinessOutbox).where(
        TimelinessOutbox.employment_fact_id == fact_id,
        TimelinessOutbox.status.in_(("pending", "processing")))))


def _current(session, fact_id):
    return list(session.scalars(select(EmploymentTimelinessResult).where(
        EmploymentTimelinessResult.employment_fact_id == fact_id,
        EmploymentTimelinessResult.status == "current")))


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    _test_correction_enqueues_both_versions(engine)
    _test_correction_result_is_not_stale_after_drain(engine)
    _test_drain_due_processes_pending_work(engine)
    _test_drain_due_is_cheap_when_queue_is_empty(engine)

    print("outbox wiring tests passed")


def _test_correction_enqueues_both_versions(engine):
    """纠错必须同时排队新旧版本：旧的要让位，新的要产生判定。"""
    with Session(engine) as session:
        ctx = _setup(session)
        old = _fact(session, ctx)
        recalculate(session, fact_id=old.id, now=D(2026, 4, 1))
        session.commit()

        new = correct_fact(session, ctx.owner, old.id,
                           actual_hire_at=D(2026, 3, 9), reason="录入错误")
        session.commit()

        assert _live_outbox(session, new.id), "新版本必须排队重算"
        assert _live_outbox(session, old.id), "旧版本必须排队，否则其结果会一直是 current"
    print("  correction enqueues both versions ok")


def _test_correction_result_is_not_stale_after_drain(engine):
    """排空后：作废事实不得再有 current 结果，新版本必须有。"""
    with Session(engine) as session:
        ctx = _setup(session)
        old = _fact(session, ctx)
        recalculate(session, fact_id=old.id, now=D(2026, 4, 1))
        session.commit()
        assert _current(session, old.id), "前置条件：旧事实先有结果"

        new = correct_fact(session, ctx.owner, old.id,
                           actual_hire_at=D(2026, 3, 9), reason="录入错误")
        session.commit()
        process_outbox(session)
        session.commit()

        assert not _current(session, old.id), \
            "已作废事实不得保留 current 结果，否则报表继续显示用户已纠正的判定"
        assert _current(session, new.id), "新版本必须有 current 结果"
    print("  corrected verdict is not stale ok")


def _test_drain_due_processes_pending_work(engine):
    """读取报表时惰性排空——沿用 scan_premium_shortfalls 的既有做法。"""
    with Session(engine) as session:
        ctx = _setup(session)
        old = _fact(session, ctx)
        recalculate(session, fact_id=old.id, now=D(2026, 4, 1))
        session.commit()
        new = correct_fact(session, ctx.owner, old.id,
                           actual_hire_at=D(2026, 3, 9), reason="x")
        session.commit()

        assert _live_outbox(session, new.id)
        drain_due(session)
        session.commit()
        assert not _live_outbox(session, new.id), "drain_due 应排空在途任务"
        assert _current(session, new.id)
    print("  drain_due processes pending ok")


def _test_drain_due_is_cheap_when_queue_is_empty(engine):
    """队列为空时不得做任何写入——它挂在每次读取路径上。"""
    with Session(engine) as session:
        ctx = _setup(session)
        result = drain_due(session)
        assert result["processed"] == 0, result
    print("  drain_due is a no-op when idle ok")


if __name__ == "__main__":
    run()
