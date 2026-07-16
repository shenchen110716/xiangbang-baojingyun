"""Fact service contract for v4.2 Phase 2 (§6.2, §20.6).

Phase 3 computes every timeliness figure from `active_facts`, so these rules
are load-bearing: only authoritative statuses are visible, scope is enforced
at the service (not the router), and a correction is an append, never an
overwrite.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "fact-service-test-key")

from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.core.id_number import id_encrypt, id_hash
from backend.models import (
    ActualEmployer,
    EmploymentFact,
    Enterprise,
    User,
    UserEmployerScope,
)
from backend.services.employment_facts import (
    FACT_EXCLUDED_STATUSES,
    active_facts,
    correct_fact,
    serialize_fact,
)

RAW_ID = "340123199001011238"


def _dt(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    enterprise = Enterprise(name="事实服务企业")
    session.add(enterprise)
    session.flush()
    ctx.enterprise = enterprise

    ctx.employer_a = ActualEmployer(enterprise_id=enterprise.id, name="项目 A")
    ctx.employer_b = ActualEmployer(enterprise_id=enterprise.id, name="项目 B")
    session.add_all([ctx.employer_a, ctx.employer_b])
    session.flush()

    ctx.owner = User(username="fs_owner", password_hash="x", name="主管",
                     role="enterprise", enterprise_id=enterprise.id,
                     enterprise_role="owner", is_owner=True)
    ctx.manager = User(username="fs_manager", password_hash="x", name="负责人",
                       role="enterprise", enterprise_id=enterprise.id,
                       enterprise_role="project_manager", is_owner=False)
    session.add_all([ctx.owner, ctx.manager])
    session.flush()

    session.add(UserEmployerScope(
        user_id=ctx.manager.id, actual_employer_id=ctx.employer_a.id,
        enterprise_id=enterprise.id, responsibility_type="primary",
        granted_by=ctx.owner.id, status="active",
        assigned_at=datetime.now(timezone.utc)))
    session.flush()
    return ctx


def _make_fact(session, ctx, *, employer=None, status="active",
               hire="2026-03-01", leave=None, id_number=RAW_ID, revision_no=1):
    employer = employer or ctx.employer_a
    fact = EmploymentFact(
        enterprise_id=ctx.enterprise.id,
        actual_employer_id=employer.id,
        person_name="张三",
        id_number_hash=id_hash(id_number) if id_number else "",
        id_number_cipher=id_encrypt(id_number) if id_number else "",
        actual_hire_at=_dt(hire),
        actual_leave_at=_dt(leave) if leave else None,
        status=status,
        revision_no=revision_no,
        created_at=datetime.now(timezone.utc),
    )
    session.add(fact)
    session.flush()
    return fact


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        ctx = _setup(session)
        _test_correct_creates_new_version_and_supersedes_previous(session, ctx)
        _test_correct_rejects_blank_reason(session, ctx)
        _test_correct_rejects_non_active_version(session, ctx)
        _test_correct_rejects_leave_before_hire(session, ctx)
        _test_correct_rejects_unauthorized_employer(session, ctx)
        _test_active_facts_excludes_non_authoritative_statuses(session, ctx)
        _test_active_facts_is_confined_to_authorized_employers(session, ctx)
        _test_serialize_never_leaks_plaintext_id(session, ctx)

    print("employment fact service tests passed")


def _clear(session):
    session.query(EmploymentFact).delete()
    session.flush()


def _test_correct_creates_new_version_and_supersedes_previous(session, ctx):
    _clear(session)
    original = _make_fact(session, ctx, hire="2026-03-01")
    new = correct_fact(session, ctx.owner, original.id,
                       actual_hire_at=_dt("2026-03-05"), reason="录入错误")
    assert new.id != original.id
    assert new.revision_no == 2
    assert new.previous_version_id == original.id
    assert new.actual_hire_at == _dt("2026-03-05")
    assert new.status == "active"
    assert original.status == "superseded"
    assert original.actual_hire_at == _dt("2026-03-01"), "旧值绝不被覆盖"
    assert new.source_event_id is None, "新版本不得复用幂等键"
    print("  correct creates new version ok")


def _test_correct_rejects_blank_reason(session, ctx):
    _clear(session)
    fact = _make_fact(session, ctx)
    try:
        correct_fact(session, ctx.owner, fact.id, actual_hire_at=_dt("2026-03-05"), reason="  ")
    except HTTPException as exc:
        assert exc.status_code == 400
    else:
        raise AssertionError("a correction without a reason must be rejected")
    print("  blank reason rejected ok")


def _test_correct_rejects_non_active_version(session, ctx):
    _clear(session)
    fact = _make_fact(session, ctx, status="superseded")
    try:
        correct_fact(session, ctx.owner, fact.id, actual_hire_at=_dt("2026-03-05"), reason="x")
    except HTTPException as exc:
        assert exc.status_code == 409
    else:
        raise AssertionError("only the current active version may be corrected")
    print("  non-active version rejected ok")


def _test_correct_rejects_leave_before_hire(session, ctx):
    _clear(session)
    fact = _make_fact(session, ctx, hire="2026-03-10")
    try:
        correct_fact(session, ctx.owner, fact.id, actual_leave_at=_dt("2026-03-01"), reason="x")
    except HTTPException as exc:
        assert exc.status_code == 400
    else:
        raise AssertionError("leave before hire must be rejected")
    print("  leave before hire rejected ok")


def _test_correct_rejects_unauthorized_employer(session, ctx):
    _clear(session)
    fact = _make_fact(session, ctx, employer=ctx.employer_b)
    try:
        correct_fact(session, ctx.manager, fact.id,
                     actual_leave_at=_dt("2026-04-01"), reason="x")
    except HTTPException as exc:
        assert exc.status_code == 403
    else:
        raise AssertionError("a manager must not correct facts outside their scope")
    print("  unauthorized employer rejected ok")


def _test_active_facts_excludes_non_authoritative_statuses(session, ctx):
    """§20.6 无真实事实、未匹配或冲突记录不进入正式指标。"""
    _clear(session)
    for status in sorted(FACT_EXCLUDED_STATUSES):
        _make_fact(session, ctx, status=status)
    keep = _make_fact(session, ctx, status="active")
    assert [f.id for f in active_facts(session, ctx.owner)] == [keep.id]
    print("  excluded statuses ok")


def _test_active_facts_is_confined_to_authorized_employers(session, ctx):
    _clear(session)
    in_scope = _make_fact(session, ctx, employer=ctx.employer_a)
    _make_fact(session, ctx, employer=ctx.employer_b)
    assert [f.id for f in active_facts(session, ctx.manager)] == [in_scope.id]
    # 主管看到本企业全部
    assert len(active_facts(session, ctx.owner)) == 2
    print("  employer scope ok")


def _test_serialize_never_leaks_plaintext_id(session, ctx):
    _clear(session)
    fact = _make_fact(session, ctx, id_number=RAW_ID)
    out = serialize_fact(fact)
    assert out["id_number"] == "340123********1238"
    assert RAW_ID not in repr(out)
    assert "id_number_cipher" not in out and "id_number_hash" not in out
    print("  serialize masks id ok")


if __name__ == "__main__":
    run()
