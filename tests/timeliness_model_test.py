"""Schema guarantees for timeliness results (v4.2 §12).

重算不得重复生成多个当前结果 (§12) — enforced by a partial unique index, not by
service-layer care, so a buggy or concurrent recalc cannot publish two current
verdicts for the same fact and have reports silently pick one.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "timeliness-model-test")

from sqlalchemy import create_engine, select
from sqlalchemy.exc import IntegrityError
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
    ParticipationOperation,
    TimelinessOutbox,
)


def _now():
    return datetime.now(timezone.utc)


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="及时率企业")
    session.add(ctx.enterprise)
    session.flush()
    ctx.employer = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    session.add(ctx.employer)
    session.flush()
    ctx.fact = EmploymentFact(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer.id,
        person_name="张三", actual_hire_at=datetime(2026, 3, 1, tzinfo=timezone.utc),
        status="active", revision_no=1, created_at=_now())
    session.add(ctx.fact)
    session.flush()
    return ctx


def _result(ctx, **kwargs):
    values = dict(
        employment_fact_id=ctx.fact.id,
        employment_fact_revision_no=1,
        operation_type="enrollment",
        enterprise_id=ctx.enterprise.id,
        actual_employer_id=ctx.employer.id,
        timeliness_status="timely",
        product_rule_version=1,
        calculation_version=1,
        calculated_at=_now(),
        status="current",
    )
    values.update(kwargs)
    return EmploymentTimelinessResult(**values)


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    _test_only_one_current_result_per_idempotency_key(engine)
    _test_superseded_rows_may_repeat_the_key(engine)
    _test_a_different_rule_version_is_a_different_key(engine)
    _test_result_status_is_constrained(engine)
    _test_responsibility_reason_is_constrained(engine)
    _test_operation_type_is_constrained(engine)
    _test_outbox_allows_only_one_live_entry_per_fact(engine)

    print("timeliness model tests passed")


def _test_only_one_current_result_per_idempotency_key(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(_result(ctx))
        session.commit()
        session.add(_result(ctx))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("§12 同一幂等键不得存在两条 current 结果")
    print("  one current per key ok")


def _test_superseded_rows_may_repeat_the_key(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        old = _result(ctx)
        session.add(old)
        session.commit()
        # 重算：旧结果先让位，新结果才能成为 current。
        old.status = "superseded"
        session.flush()
        session.add(_result(ctx))
        session.commit()
        rows = list(session.scalars(select(EmploymentTimelinessResult).where(
            EmploymentTimelinessResult.employment_fact_id == ctx.fact.id)))
        assert len(rows) == 2
        assert sum(1 for r in rows if r.status == "current") == 1
    print("  superseded may repeat key ok")


def _test_a_different_rule_version_is_a_different_key(engine):
    """算法升级后可以并存新版本结果，旧版本仍可审计。"""
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(_result(ctx, product_rule_version=1))
        session.add(_result(ctx, product_rule_version=2))
        session.commit()
        rows = list(session.scalars(select(EmploymentTimelinessResult).where(
            EmploymentTimelinessResult.employment_fact_id == ctx.fact.id)))
        assert len(rows) == 2, rows
    print("  rule version separates keys ok")


def _test_result_status_is_constrained(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(_result(ctx, timeliness_status="nonsense"))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("未知及时率状态必须被约束拒绝")
    print("  result status constrained ok")


def _test_responsibility_reason_is_constrained(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(_result(ctx, responsibility_reason="blame_someone"))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("未知责任原因必须被约束拒绝")
    print("  responsibility reason constrained ok")


def _test_operation_type_is_constrained(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(ParticipationOperation(
            enterprise_id=ctx.enterprise.id, operation_type="nonsense",
            submitted_at=_now()))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("未知操作类型必须被约束拒绝")
    print("  operation type constrained ok")


def _test_outbox_allows_only_one_live_entry_per_fact(engine):
    """同一事实不得堆积多条待处理重算任务，否则会重复计算。"""
    with Session(engine) as session:
        ctx = _setup(session)
        session.add(TimelinessOutbox(employment_fact_id=ctx.fact.id,
                                     status="pending", created_at=_now()))
        session.commit()
        session.add(TimelinessOutbox(employment_fact_id=ctx.fact.id,
                                     status="pending", created_at=_now()))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("同一事实只允许一条在途 outbox 任务")

    with Session(engine) as session:
        ctx = _setup(session)
        done = TimelinessOutbox(employment_fact_id=ctx.fact.id, status="pending",
                                created_at=_now())
        session.add(done)
        session.commit()
        done.status = "done"
        session.flush()
        # 处理完毕后允许再次入队
        session.add(TimelinessOutbox(employment_fact_id=ctx.fact.id,
                                     status="pending", created_at=_now()))
        session.commit()
    print("  outbox single live entry ok")


if __name__ == "__main__":
    run()
