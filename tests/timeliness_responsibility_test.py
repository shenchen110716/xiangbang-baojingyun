"""Responsibility attribution (v4.2 §11.3).

The rule that matters most here: 当时没有主要负责人时为 unassigned_responsibility，
不得归给当前管理员. Blaming whoever happens to hold the role today for something
that happened before they held it is worse than admitting nobody was assigned —
it produces a confident, wrong number that someone will be judged on.

Event-time lookup is possible because Phase 1 stored employer scopes
historically (assigned_at/revoked_at) rather than as current state.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "responsibility-test")

from sqlalchemy import create_engine
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    ActualEmployer,
    EmploymentFact,
    Enterprise,
    ParticipationOperation,
    User,
    UserEmployerScope,
)
from backend.services.timeliness_engine import Verdict
from backend.services.timeliness_responsibility import attribute


def D(*args):
    return datetime(*args, tzinfo=timezone.utc)


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="责任企业")
    session.add(ctx.enterprise)
    session.flush()
    ctx.employer = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    session.add(ctx.employer)
    session.flush()
    ctx.owner = User(username="r_owner", password_hash="x", name="主管",
                     role="enterprise", enterprise_id=ctx.enterprise.id,
                     enterprise_role="owner", is_owner=True)
    session.add(ctx.owner)
    session.flush()
    return ctx


def _user(session, ctx, username):
    u = User(username=username, password_hash="x", name=username, role="enterprise",
             enterprise_id=ctx.enterprise.id, enterprise_role="project_manager")
    session.add(u)
    session.flush()
    return u


def _grant_primary(session, ctx, user, assigned_at, revoked_at=None):
    session.add(UserEmployerScope(
        user_id=user.id, enterprise_id=ctx.enterprise.id,
        actual_employer_id=ctx.employer.id, responsibility_type="primary",
        granted_by=ctx.owner.id, assigned_at=assigned_at, revoked_at=revoked_at,
        status="active" if revoked_at is None else "revoked"))
    session.flush()


def _fact(session, ctx, *, hire=None, reported_at=None):
    f = EmploymentFact(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer.id,
        person_name="张三", actual_hire_at=hire or D(2026, 3, 1),
        feedback_reported_at=reported_at, status="active", revision_no=1,
        created_at=D(2026, 3, 1))
    session.add(f)
    session.flush()
    return f


def _op(session, ctx, *, submitted_by=None, batch_id=None,
        submitted_at=None, system_sent_at=None, insurer_confirmed_at=None):
    o = ParticipationOperation(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer.id,
        operation_type="enrollment", submitted_by=submitted_by, batch_id=batch_id,
        submitted_at=submitted_at or D(2026, 3, 1, 10),
        system_sent_at=system_sent_at, insurer_confirmed_at=insurer_confirmed_at)
    session.add(o)
    session.flush()
    return o


TIMELY = Verdict("timely")
LATE = Verdict("late", delay_seconds=3 * 86400)
MISSING = Verdict("missing")


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    _test_successful_operation_is_attributed_to_the_submitter(engine)
    _test_batch_row_is_attributed_to_the_confirming_uploader(engine)
    _test_missing_event_uses_event_time_primary_manager(engine)
    _test_no_primary_manager_at_event_time_is_unassigned(engine)
    _test_revoked_manager_still_owns_their_era(engine)
    _test_evidence_records_the_full_time_chain(engine)

    print("timeliness responsibility tests passed")


def _test_successful_operation_is_attributed_to_the_submitter(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        submitter = _user(session, ctx, "submitter7")
        reason, uid, _ = attribute(session, fact=_fact(session, ctx), verdict=TIMELY,
                                   operation=_op(session, ctx, submitted_by=submitter.id))
        assert uid == submitter.id
        assert reason == "normal"
    print("  timely attributed to submitter ok")


def _test_batch_row_is_attributed_to_the_confirming_uploader(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        uploader = _user(session, ctx, "uploader9")
        _, uid, _ = attribute(session, fact=_fact(session, ctx), verdict=LATE,
                              operation=_op(session, ctx, submitted_by=uploader.id, batch_id=3))
        assert uid == uploader.id
    print("  batch attributed to uploader ok")


def _test_missing_event_uses_event_time_primary_manager(engine):
    """§11.3 按事件发生时的主要负责人，而非当前负责人。"""
    with Session(engine) as session:
        ctx = _setup(session)
        past = _user(session, ctx, "manager_then")
        now_mgr = _user(session, ctx, "manager_now")
        _grant_primary(session, ctx, past, assigned_at=D(2026, 1, 1), revoked_at=D(2026, 4, 1))
        _grant_primary(session, ctx, now_mgr, assigned_at=D(2026, 4, 1))

        _, uid, _ = attribute(session, fact=_fact(session, ctx, hire=D(2026, 3, 1)),
                              verdict=MISSING, operation=None)
        assert uid == past.id, "必须归给事件当时的负责人，而不是现任"
        assert uid != now_mgr.id
    print("  event-time manager ok")


def _test_no_primary_manager_at_event_time_is_unassigned(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        later = _user(session, ctx, "manager_later")
        _grant_primary(session, ctx, later, assigned_at=D(2026, 6, 1))

        reason, uid, _ = attribute(session, fact=_fact(session, ctx, hire=D(2026, 3, 1)),
                                   verdict=MISSING, operation=None)
        assert reason == "unassigned_responsibility"
        assert uid is None, "§11.3 不得归给当前管理员"
    print("  unassigned when nobody held the role ok")


def _test_revoked_manager_still_owns_their_era(engine):
    with Session(engine) as session:
        ctx = _setup(session)
        gone = _user(session, ctx, "manager_gone")
        _grant_primary(session, ctx, gone, assigned_at=D(2026, 1, 1), revoked_at=D(2026, 2, 1))

        # 事件在其任期内：即便授权早已撤销，责任仍属于他。
        _, uid, _ = attribute(session, fact=_fact(session, ctx, hire=D(2026, 1, 15)),
                              verdict=MISSING, operation=None)
        assert uid == gone.id

        # 事件在其任期之后：不得归给他。
        reason, uid2, _ = attribute(session, fact=_fact(session, ctx, hire=D(2026, 3, 1)),
                                    verdict=MISSING, operation=None)
        assert uid2 is None and reason == "unassigned_responsibility"
    print("  revoked manager owns only their era ok")


def _test_evidence_records_the_full_time_chain(engine):
    """单一主责用于聚合；完整时间链留在 evidence 里供申辩。"""
    with Session(engine) as session:
        ctx = _setup(session)
        submitter = _user(session, ctx, "submitter_ev")
        fact = _fact(session, ctx, reported_at=D(2026, 3, 1, 9))
        op = _op(session, ctx, submitted_by=submitter.id,
                 submitted_at=D(2026, 3, 1, 10),
                 system_sent_at=D(2026, 3, 1, 11),
                 insurer_confirmed_at=D(2026, 3, 1, 12))
        _, _, ev = attribute(session, fact=fact, verdict=LATE, operation=op)
        assert set(ev) >= {"feedback_reported_at", "submitted_at",
                           "system_sent_at", "insurer_confirmed_at"}
    print("  evidence chain ok")


if __name__ == "__main__":
    run()
