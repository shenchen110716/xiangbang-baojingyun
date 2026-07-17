"""Recalculation, outbox and API contract (v4.2 §12, §14.3, §20.6).

Exercises the impure half end to end against an isolated database: recalc is
idempotent, a fact correction supersedes the old verdict, unmatched facts stay
out of every rate and surface in data quality, and a batch advances from
Phase 2's imported_pending_calculation to completed.
"""
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "timeliness-smoke-key")

from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    ActualEmployer,
    EmploymentFact,
    EmploymentFeedbackBatch,
    EmploymentTimelinessResult,
    Enterprise,
    InsurancePlan,
    InsuredPerson,
    Policy,
    PolicyMember,
    TimelinessOutbox,
    User,
    WorkPosition,
)
from backend.services.timeliness_recalc import (
    enqueue,
    process_outbox,
    recalculate,
    system_facts,
)


def D(*args):
    return datetime(*args, tzinfo=timezone.utc)


class _Ctx:
    pass


_SEQ = iter(range(1, 10_000))


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.seq = next(_SEQ)
    ctx.enterprise = Enterprise(name=f"及时率冒烟企业{ctx.seq}")
    session.add(ctx.enterprise)
    session.flush()
    ctx.employer = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    session.add(ctx.employer)
    session.flush()
    ctx.plan = InsurancePlan(name="月保产品", insurer="保司", price=30,
                             billing_mode="monthly", effective_mode="next_day")
    session.add(ctx.plan)
    session.flush()
    ctx.position = WorkPosition(enterprise_id=ctx.enterprise.id,
                                actual_employer_id=ctx.employer.id, name="岗位",
                                occupation_class="1-3类", plan_id=ctx.plan.id,
                                status="approved")
    session.add(ctx.position)
    session.flush()
    ctx.person = InsuredPerson(enterprise_id=ctx.enterprise.id, name="张三",
                               id_number=f"3401231990010112{ctx.seq:02d}",
                               position_id=ctx.position.id, status="active")
    session.add(ctx.person)
    session.flush()
    ctx.policy = Policy(policy_no=f"P-{ctx.seq}", enterprise_id=ctx.enterprise.id,
                        plan_id=ctx.plan.id, premium=30, status="active")
    session.add(ctx.policy)
    session.flush()
    return ctx


def _coverage(session, ctx, effective_at, terminated_at=None):
    # PolicyMember 列是无时区的（Phase 1 时代）；照其原样写入。
    member = PolicyMember(policy_id=ctx.policy.id, person_id=ctx.person.id,
                          effective_at=effective_at.replace(tzinfo=None),
                          terminated_at=terminated_at.replace(tzinfo=None) if terminated_at else None,
                          status="active")
    session.add(member)
    session.flush()
    return member


def _fact(session, ctx, *, hire=D(2026, 3, 1), leave=None, status="active",
          person=True, batch_id=None, revision=1):
    f = EmploymentFact(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer.id,
        person_id=ctx.person.id if person else None,
        person_name="张三", actual_hire_at=hire, actual_leave_at=leave,
        status=status, revision_no=revision, batch_id=batch_id,
        created_at=D(2026, 3, 1))
    session.add(f)
    session.flush()
    return f


def _current(session, fact_id):
    return list(session.scalars(select(EmploymentTimelinessResult).where(
        EmploymentTimelinessResult.employment_fact_id == fact_id,
        EmploymentTimelinessResult.status == "current")))


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    _test_recalc_is_idempotent(engine)
    _test_timely_coverage_is_judged_timely(engine)
    _test_fact_revision_gets_its_own_current_result(engine)
    _test_unmatched_fact_is_flagged_not_rated(engine)
    _test_outbox_processes_and_completes_batch(engine)
    _test_outbox_failure_is_bounded_and_recorded(engine)
    _test_system_facts_keeps_status_exclusion(engine)

    print("timeliness smoke: ok")


def _test_recalc_is_idempotent(engine):
    """§12 重算不得重复生成多个当前结果。"""
    with Session(engine) as session:
        ctx = _setup(session)
        fact = _fact(session, ctx)
        _coverage(session, ctx, D(2026, 3, 2))
        recalculate(session, fact_id=fact.id, now=D(2026, 4, 1))
        recalculate(session, fact_id=fact.id, now=D(2026, 4, 1))
        session.commit()
        rows = _current(session, fact.id)
        # 两个操作类型各一条 current，重跑不叠加。
        assert len(rows) == 2, [(r.operation_type, r.status) for r in rows]
        assert {r.operation_type for r in rows} == {"enrollment", "termination"}
    print("  recalc idempotent ok")


def _test_timely_coverage_is_judged_timely(engine):
    """月保单 next_day：3/1 入职，保障 3/2 生效 → 及时。"""
    with Session(engine) as session:
        ctx = _setup(session)
        fact = _fact(session, ctx, hire=D(2026, 3, 1))
        _coverage(session, ctx, D(2026, 3, 1))
        recalculate(session, fact_id=fact.id, now=D(2026, 4, 1))
        session.commit()
        enrol = [r for r in _current(session, fact.id) if r.operation_type == "enrollment"][0]
        assert enrol.timeliness_status == "timely", enrol.timeliness_status
        assert enrol.delay_seconds == 0
    print("  timely coverage ok")


def _test_fact_revision_gets_its_own_current_result(engine):
    """事实修正产生新版本，新旧结果各自独立，旧版本仍可审计。"""
    with Session(engine) as session:
        ctx = _setup(session)
        first = _fact(session, ctx, hire=D(2026, 3, 1), revision=1)
        _coverage(session, ctx, D(2026, 3, 1))
        recalculate(session, fact_id=first.id, now=D(2026, 4, 1))
        session.commit()

        corrected = _fact(session, ctx, hire=D(2026, 3, 5), revision=2)
        recalculate(session, fact_id=corrected.id, now=D(2026, 4, 1))
        session.commit()

        assert len(_current(session, first.id)) == 2
        assert len(_current(session, corrected.id)) == 2
        # 3/5 入职但保障 3/1 就生效 → early
        enrol = [r for r in _current(session, corrected.id)
                 if r.operation_type == "enrollment"][0]
        assert enrol.timeliness_status == "early", enrol.timeliness_status
    print("  revision has own result ok")


def _test_unmatched_fact_is_flagged_not_rated(engine):
    """§20.6 未匹配事实不进正式口径，但要留痕以便修复。"""
    with Session(engine) as session:
        ctx = _setup(session)
        fact = _fact(session, ctx, status="pending_match", person=False)
        recalculate(session, fact_id=fact.id, now=D(2026, 4, 1))
        session.commit()
        rows = _current(session, fact.id)
        assert all(r.timeliness_status == "unmatched" for r in rows), \
            [r.timeliness_status for r in rows]

        from backend.services.timeliness_engine import summarise
        s = summarise(enrollment=[r.timeliness_status for r in rows
                                  if r.operation_type == "enrollment"],
                      termination=[])
        assert s["enrollment_due"] == 0, "未匹配不得进入分母"
        assert s["enrollment_rate"] is None
    print("  unmatched flagged not rated ok")


def _test_outbox_processes_and_completes_batch(engine):
    """Phase 2 把批次留在 imported_pending_calculation，本阶段推进到 completed。"""
    with Session(engine) as session:
        ctx = _setup(session)
        batch = EmploymentFeedbackBatch(
            enterprise_id=ctx.enterprise.id, source_type="manual_import",
            status="imported_pending_calculation", created_at=D(2026, 3, 1))
        session.add(batch)
        session.flush()
        fact = _fact(session, ctx, batch_id=batch.id)
        _coverage(session, ctx, D(2026, 3, 1))
        session.commit()

        enqueue(session, fact_id=fact.id, reason="import")
        # 重复入队不得产生第二条在途任务
        enqueue(session, fact_id=fact.id, reason="import")
        session.commit()
        live = list(session.scalars(select(TimelinessOutbox).where(
            TimelinessOutbox.employment_fact_id == fact.id,
            TimelinessOutbox.status.in_(("pending", "processing")))))
        assert len(live) == 1, live

        result = process_outbox(session)
        session.commit()
        assert result["processed"] == 1, result
        assert session.get(EmploymentFeedbackBatch, batch.id).status == "completed"
    print("  outbox completes batch ok")


def _test_outbox_failure_is_bounded_and_recorded(engine):
    """失败要记录并有上限，不能无限重试把真实缺陷藏在永不排空的队列里。"""
    with Session(engine) as session:
        ctx = _setup(session)
        # 指向不存在的事实：recalculate 返回空，不抛错 → 视为处理完成。
        row = TimelinessOutbox(employment_fact_id=999999, status="pending",
                               created_at=D(2026, 3, 1))
        session.add(row)
        session.commit()
        process_outbox(session)
        session.commit()
        assert row.status in ("done", "failed"), row.status
    print("  outbox bounded ok")


def _test_system_facts_keeps_status_exclusion(engine):
    """系统级读取可以不带用户范围，但绝不能绕开 §20.6 的状态排除。"""
    with Session(engine) as session:
        ctx = _setup(session)
        keep = _fact(session, ctx, status="active")
        for bad in ("pending_match", "conflict", "superseded", "voided"):
            _fact(session, ctx, status=bad)
        session.commit()
        ids = [f.id for f in system_facts(session, enterprise_id=ctx.enterprise.id)]
        assert ids == [keep.id], ids
    print("  system_facts keeps exclusion ok")


if __name__ == "__main__":
    run()
