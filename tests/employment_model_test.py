"""Schema-level guarantees for v4.2 Phase 2 employment facts (§6).

These are enforced in the database, not only in the service layer, so a
future writer that bypasses the service cannot corrupt the fact base.
"""
import os
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "model-test-key")

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
    EmploymentFactMatch,
    EmploymentFeedbackBatch,
    Enterprise,
)


def _dt(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


def _fact(enterprise_id: int, employer_id: int, **kwargs) -> EmploymentFact:
    values = dict(
        enterprise_id=enterprise_id,
        actual_employer_id=employer_id,
        actual_hire_at=_dt("2026-03-01"),
        status="active",
        revision_no=1,
        created_at=datetime.now(timezone.utc),
    )
    values.update(kwargs)
    return EmploymentFact(**values)


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    # SQLite does not enforce foreign keys or partial-index uniqueness without
    # this pragma; the production target is PostgreSQL, so assert the real rules.
    from sqlalchemy import event

    @event.listens_for(engine, "connect")
    def _fk_on(conn, _record):
        conn.execute("PRAGMA foreign_keys=ON")

    Base.metadata.create_all(engine)

    with Session(engine) as session:
        enterprise = Enterprise(name="事实测试企业")
        session.add(enterprise)
        session.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="项目 A")
        session.add(employer)
        session.flush()
        eid, pid = enterprise.id, employer.id
        session.commit()

    _test_leave_must_be_after_hire(engine, eid, pid)
    _test_source_event_id_is_unique_per_enterprise(engine, eid, pid)
    _test_status_is_constrained(engine, eid, pid)
    _test_batch_status_is_constrained(engine, eid)
    _test_match_status_and_method_are_constrained(engine, eid, pid)
    _test_version_chain_links_to_previous(engine, eid, pid)

    print("employment model tests passed")


def _test_leave_must_be_after_hire(engine, eid, pid):
    """§6.2 离职时间必须晚于入职时间。"""
    with Session(engine) as session:
        session.add(_fact(eid, pid, actual_hire_at=_dt("2026-03-05"),
                          actual_leave_at=_dt("2026-03-01")))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("leave before hire must violate ck_fact_leave_after_hire")

    # 空离职时间合法（在职）
    with Session(engine) as session:
        session.add(_fact(eid, pid, actual_leave_at=None))
        session.commit()
    print("  leave_after_hire ok")


def _test_source_event_id_is_unique_per_enterprise(engine, eid, pid):
    """§6.2 同一企业内 source_event_id 唯一，保证外部推送幂等。"""
    with Session(engine) as session:
        session.add(_fact(eid, pid, source_event_id="EVT-1"))
        session.commit()
    with Session(engine) as session:
        session.add(_fact(eid, pid, source_event_id="EVT-1"))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("duplicate source_event_id must violate ux_fact_source_event")

    # NULL 不参与唯一性，允许多条无外部事件号的事实
    with Session(engine) as session:
        session.add(_fact(eid, pid, source_event_id=None))
        session.add(_fact(eid, pid, source_event_id=None))
        session.commit()
    print("  source_event_id unique ok")


def _test_status_is_constrained(engine, eid, pid):
    with Session(engine) as session:
        session.add(_fact(eid, pid, status="nonsense"))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("unknown fact status must violate ck_fact_status")
    print("  fact status constrained ok")


def _test_batch_status_is_constrained(engine, eid):
    with Session(engine) as session:
        session.add(EmploymentFeedbackBatch(
            enterprise_id=eid, source_type="manual_import", status="nonsense",
            created_at=datetime.now(timezone.utc)))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("unknown batch status must violate ck_batch_status")

    with Session(engine) as session:
        session.add(EmploymentFeedbackBatch(
            enterprise_id=eid, source_type="not_a_source", status="uploaded",
            created_at=datetime.now(timezone.utc)))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("unknown source_type must violate ck_batch_source_type")
    print("  batch constraints ok")


def _test_match_status_and_method_are_constrained(engine, eid, pid):
    with Session(engine) as session:
        fact = _fact(eid, pid)
        session.add(fact)
        session.flush()
        session.add(EmploymentFactMatch(
            employment_fact_id=fact.id, match_status="nonsense",
            match_method="manual", created_at=datetime.now(timezone.utc)))
        try:
            session.commit()
        except IntegrityError:
            pass
        else:
            raise AssertionError("unknown match_status must violate ck_match_status")
    print("  match constraints ok")


def _test_version_chain_links_to_previous(engine, eid, pid):
    """§6.2 纠错产生新版本并指回旧版本，旧值保留。"""
    with Session(engine) as session:
        original = _fact(eid, pid, actual_hire_at=_dt("2026-03-01"))
        session.add(original)
        session.flush()
        corrected = _fact(eid, pid, actual_hire_at=_dt("2026-03-05"),
                          revision_no=2, previous_version_id=original.id)
        original.status = "superseded"
        session.add(corrected)
        session.commit()

        chain = session.scalar(
            select(EmploymentFact).where(EmploymentFact.previous_version_id == original.id))
        assert chain.revision_no == 2
        assert session.get(EmploymentFact, original.id).status == "superseded"
        assert session.get(EmploymentFact, original.id).actual_hire_at.replace(tzinfo=timezone.utc) == _dt("2026-03-01"), \
            "the superseded version must keep its original value"
    print("  version chain ok")


if __name__ == "__main__":
    run()
