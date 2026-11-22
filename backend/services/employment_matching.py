"""Bind an incoming employment fact to an insured person (v4.2 §6.4).

Priority ladder:

1. ``external_employment_id`` within the data source.
2. enterprise + actual employer + ID hash + real hire date.
3. ``external_employee_no`` within enterprise + actual employer.
4. manual.

Rungs 1 and 3 resolve through prior ``EmploymentFact`` rows, since
``InsuredPerson`` carries neither identifier. Rung 2 resolves against
``InsuredPerson`` directly so a first-ever import can bind without waiting for
a human.

Every rung is scoped by enterprise, and rungs 2-3 additionally by actual
employer, so a match can never cross a tenant or a project boundary. More than
one candidate yields ``ambiguous`` rather than a guess: a wrong binding would
silently misattribute every timeliness figure Phase 3 computes for that person.
"""
from datetime import datetime
from typing import NamedTuple, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.id_number import id_hash
from ..models import EmploymentFact, InsuredPerson, WorkPosition


class MatchResult(NamedTuple):
    status: str          # matched | pending | ambiguous | rejected
    method: str          # external_employment_id | identity_hire | employee_no | manual
    person_id: Optional[int]
    confidence: float
    reason: str


def _fact_person_ids(session: Session, stmt) -> list[int]:
    return sorted({pid for pid in session.scalars(stmt) if pid is not None})


def _by_external_employment_id(session, enterprise_id, external_employment_id) -> list[int]:
    if not external_employment_id:
        return []
    return _fact_person_ids(session, select(EmploymentFact.person_id).where(
        EmploymentFact.enterprise_id == enterprise_id,
        EmploymentFact.external_employment_id == external_employment_id,
        EmploymentFact.status.in_(("active", "superseded")),
    ))


def _by_identity(session, enterprise_id, actual_employer_id, id_number) -> list[int]:
    """story-p3-compliance：InsuredPerson 现在带 id_number_hash 列（双写期
    由 ORM 事件维护），直接用索引等值匹配，不再全表取明文逐行哈希。"""
    if not id_number:
        return []
    stmt = (
        select(InsuredPerson.id)
        .join(WorkPosition, InsuredPerson.position_id == WorkPosition.id)
        .where(
            InsuredPerson.enterprise_id == enterprise_id,
            WorkPosition.actual_employer_id == actual_employer_id,
            InsuredPerson.id_number_hash == id_hash(id_number),
        )
    )
    return sorted(session.scalars(stmt))


def _by_employee_no(session, enterprise_id, actual_employer_id, external_employee_no) -> list[int]:
    if not external_employee_no:
        return []
    return _fact_person_ids(session, select(EmploymentFact.person_id).where(
        EmploymentFact.enterprise_id == enterprise_id,
        EmploymentFact.actual_employer_id == actual_employer_id,
        EmploymentFact.external_employee_no == external_employee_no,
        EmploymentFact.status.in_(("active", "superseded")),
    ))


def match_person(
    session: Session,
    *,
    enterprise_id: int,
    actual_employer_id: int,
    external_employment_id: str = "",
    id_number: str = "",
    actual_hire_at: Optional[datetime] = None,
    external_employee_no: str = "",
) -> MatchResult:
    ladder = (
        ("external_employment_id", 1.0,
         lambda: _by_external_employment_id(session, enterprise_id, external_employment_id)),
        ("identity_hire", 0.9,
         lambda: _by_identity(session, enterprise_id, actual_employer_id, id_number)),
        ("employee_no", 0.7,
         lambda: _by_employee_no(session, enterprise_id, actual_employer_id, external_employee_no)),
    )

    for method, confidence, lookup in ladder:
        candidates = lookup()
        if len(candidates) == 1:
            return MatchResult("matched", method, candidates[0], confidence,
                               f"按{method}唯一匹配")
        if len(candidates) > 1:
            # Stop here rather than falling through: a weaker rung agreeing by
            # coincidence would paper over a real data-quality conflict.
            return MatchResult("ambiguous", method, None, 0.0,
                               f"按{method}匹配到 {len(candidates)} 个候选，需人工确认")

    return MatchResult("pending", "manual", None, 0.0, "未找到候选人员，需人工匹配")
