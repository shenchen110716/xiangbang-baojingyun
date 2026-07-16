"""Identity matching ladder for v4.2 §6.4.

Priority: (1) external_employment_id within the data source, (2) enterprise +
actual employer + ID hash + real hire date, (3) external employee number within
enterprise + employer, (4) manual.

Rungs 1 and 3 resolve through prior EmploymentFact rows, because InsuredPerson
carries neither an external employment id nor an employee number. Rung 2
resolves against InsuredPerson so a first-ever import can still bind to a
person. More than one candidate is `ambiguous`, never a guess: a wrong binding
silently corrupts every Phase 3 figure for that person.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "matching-test-key")

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
    InsuredPerson,
    WorkPosition,
)
from backend.services.employment_matching import match_person

RAW = "340123199001011234"
OTHER_ID = "11010119900307771X"
UNKNOWN_ID = "500103199003076116"


def _dt(text: str) -> datetime:
    return datetime.fromisoformat(text).replace(tzinfo=timezone.utc)


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="匹配企业")
    ctx.other_enterprise = Enterprise(name="他企业")
    session.add_all([ctx.enterprise, ctx.other_enterprise])
    session.flush()

    ctx.employer_a = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    ctx.employer_b = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 B")
    ctx.other_employer = ActualEmployer(enterprise_id=ctx.other_enterprise.id, name="他单位")
    session.add_all([ctx.employer_a, ctx.employer_b, ctx.other_employer])
    session.flush()
    return ctx


def _make_person(session, ctx, *, id_number=RAW, employer=None, enterprise=None, name="张三"):
    enterprise = enterprise or ctx.enterprise
    employer = employer or ctx.employer_a
    position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                            name="岗位", occupation_class="1-3类")
    session.add(position)
    session.flush()
    person = InsuredPerson(enterprise_id=enterprise.id, name=name,
                           id_number=id_number, position_id=position.id)
    session.add(person)
    session.flush()
    return person


def _make_fact(session, ctx, *, person, employer=None, external_employment_id="",
               external_employee_no="", id_number=RAW, hire="2026-03-01"):
    employer = employer or ctx.employer_a
    fact = EmploymentFact(
        enterprise_id=ctx.enterprise.id, actual_employer_id=employer.id,
        person_id=person.id, external_employment_id=external_employment_id,
        external_employee_no=external_employee_no,
        id_number_hash=id_hash(id_number) if id_number else "",
        id_number_cipher=id_encrypt(id_number) if id_number else "",
        person_name=person.name, actual_hire_at=_dt(hire), status="active",
        created_at=datetime.now(timezone.utc))
    session.add(fact)
    session.flush()
    return fact


def _call(session, ctx, **kwargs):
    params = dict(
        enterprise_id=ctx.enterprise.id,
        actual_employer_id=ctx.employer_a.id,
        external_employment_id="",
        id_number="",
        actual_hire_at=_dt("2026-03-01"),
        external_employee_no="",
    )
    params.update(kwargs)
    return match_person(session, **params)


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        ctx = _setup(session)
        _test_priority_1_external_employment_id_wins(session, ctx)
        _test_priority_2_identity_plus_hire_date(session, ctx)
        _test_priority_3_employee_no_within_employer(session, ctx)
        _test_multiple_candidates_are_ambiguous(session, ctx)
        _test_no_candidate_is_pending(session, ctx)
        _test_match_never_crosses_enterprise(session, ctx)
        _test_match_never_crosses_employer(session, ctx)

    print("employment matching tests passed")


def _clear(session):
    session.query(EmploymentFact).delete()
    session.query(InsuredPerson).delete()
    session.query(WorkPosition).delete()
    session.flush()


def _test_priority_1_external_employment_id_wins(session, ctx):
    """外部用工记录号优先级最高，即使身份证指向另一个人。"""
    _clear(session)
    bound = _make_person(session, ctx, id_number=OTHER_ID, name="记录号本人")
    _make_fact(session, ctx, person=bound, external_employment_id="EXT-1", id_number=OTHER_ID)
    decoy = _make_person(session, ctx, id_number=RAW, name="身份证同号者")

    result = _call(session, ctx, external_employment_id="EXT-1", id_number=RAW)
    assert result.status == "matched", result
    assert result.method == "external_employment_id", result
    assert result.person_id == bound.id, f"rung 1 must win over identity, got {result.person_id}"
    assert result.person_id != decoy.id
    print("  priority 1 external_employment_id ok")


def _test_priority_2_identity_plus_hire_date(session, ctx):
    _clear(session)
    person = _make_person(session, ctx, id_number=RAW)
    result = _call(session, ctx, id_number=RAW, actual_hire_at=_dt("2026-03-01"))
    assert result.status == "matched", result
    assert result.method == "identity_hire", result
    assert result.person_id == person.id
    print("  priority 2 identity_hire ok")


def _test_priority_3_employee_no_within_employer(session, ctx):
    _clear(session)
    person = _make_person(session, ctx, id_number=OTHER_ID)
    _make_fact(session, ctx, person=person, external_employee_no="E001", id_number=OTHER_ID)

    result = _call(session, ctx, external_employee_no="E001")
    assert result.status == "matched", result
    assert result.method == "employee_no", result
    assert result.person_id == person.id
    print("  priority 3 employee_no ok")


def _test_multiple_candidates_are_ambiguous(session, ctx):
    """两个同身份证候选人时必须 ambiguous，不得猜一个。"""
    _clear(session)
    _make_person(session, ctx, id_number=RAW, name="甲")
    _make_person(session, ctx, id_number=RAW, name="乙")
    result = _call(session, ctx, id_number=RAW)
    assert result.status == "ambiguous", result
    assert result.person_id is None, "an ambiguous match must not bind a person"
    print("  ambiguous ok")


def _test_no_candidate_is_pending(session, ctx):
    _clear(session)
    _make_person(session, ctx, id_number=RAW)
    result = _call(session, ctx, id_number=UNKNOWN_ID)
    assert result.status == "pending", result
    assert result.person_id is None
    print("  pending ok")


def _test_match_never_crosses_enterprise(session, ctx):
    _clear(session)
    _make_person(session, ctx, id_number=RAW,
                 enterprise=ctx.other_enterprise, employer=ctx.other_employer)
    result = _call(session, ctx, id_number=RAW)
    assert result.status == "pending", f"must not match across enterprises: {result}"
    print("  enterprise isolation ok")


def _test_match_never_crosses_employer(session, ctx):
    _clear(session)
    _make_person(session, ctx, id_number=RAW, employer=ctx.employer_b)
    result = _call(session, ctx, id_number=RAW)
    assert result.status == "pending", f"must not match across actual employers: {result}"
    print("  employer isolation ok")


if __name__ == "__main__":
    run()
