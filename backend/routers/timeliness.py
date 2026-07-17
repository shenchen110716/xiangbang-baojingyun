"""Timeliness query and recalculation APIs (v4.2 §14.3).

Reads scope through Phase 1's `allowed_employer_ids`; recalculation enqueues
rather than computing inline, so a large enterprise cannot hold a request open
while every fact is judged.

`GET /api/timeliness/export` is Phase 4 — XLSX ships with the views.
"""
import json

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import EmploymentFact, EmploymentTimelinessResult, User
from ..services.employer_scopes import allowed_employer_ids, is_enterprise_owner
from ..services.timeliness_recalc import enqueue, process_outbox, system_facts
from ..services.timeliness_reporting import (
    build_export, detail_rows, summary_for,
)

router = APIRouter(prefix="/api", tags=["timeliness"])

_ENTERPRISE_OR_ADMIN = require_role("admin", "enterprise", detail="无权访问及时率数据")

# 未匹配或有争议的记录不进正式口径，只出现在数据质量队列（§20.6）。
_DATA_QUALITY_STATUSES = ("unmatched", "conflict")


def _scoped_results(session: Session, user: User):
    stmt = select(EmploymentTimelinessResult).where(
        EmploymentTimelinessResult.status == "current")
    allowed = allowed_employer_ids(session, user)
    if allowed is not None:
        if not allowed:
            return None
        stmt = stmt.where(EmploymentTimelinessResult.actual_employer_id.in_(allowed))
    if user.role == "enterprise":
        stmt = stmt.where(EmploymentTimelinessResult.enterprise_id == user.enterprise_id)
    return stmt


def _rows(session: Session, user: User) -> list[EmploymentTimelinessResult]:
    stmt = _scoped_results(session, user)
    if stmt is None:
        return []
    return list(session.scalars(stmt.order_by(EmploymentTimelinessResult.id)))


def _serialize(row: EmploymentTimelinessResult) -> dict:
    try:
        evidence = json.loads(row.responsibility_evidence_json or "{}")
    except json.JSONDecodeError:
        evidence = {}
    return {
        "id": row.id,
        "employment_fact_id": row.employment_fact_id,
        "employment_fact_revision_no": row.employment_fact_revision_no,
        "operation_type": row.operation_type,
        "enterprise_id": row.enterprise_id,
        "actual_employer_id": row.actual_employer_id,
        "person_id": row.person_id,
        "responsible_user_id": row.responsible_user_id,
        "actual_business_at": row.actual_business_at,
        "expected_coverage_at": row.expected_coverage_at,
        "actual_coverage_at": row.actual_coverage_at,
        "timeliness_status": row.timeliness_status,
        "delay_seconds": row.delay_seconds,
        "early_seconds": row.early_seconds,
        "coverage_gap_seconds": row.coverage_gap_seconds,
        "feedback_status": row.feedback_status,
        "responsibility_reason": row.responsibility_reason,
        "responsibility_evidence": evidence,
        "product_rule_version": row.product_rule_version,
        "calculation_version": row.calculation_version,
        "calculated_at": row.calculated_at,
    }


@router.get("/timeliness/summary", dependencies=[Depends(_ENTERPRISE_OR_ADMIN)])
def timeliness_summary(
    operation_type: str | None = Query(None),
    actual_employer_id: int | None = Query(None),
    responsible_user_id: int | None = Query(None),
    since: str | None = Query(None),
    until: str | None = Query(None),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    return summary_for(session, user, operation_type=operation_type,
                       actual_employer_id=actual_employer_id,
                       responsible_user_id=responsible_user_id,
                       since=since, until=until)


@router.get("/timeliness/details", dependencies=[Depends(_ENTERPRISE_OR_ADMIN)])
def timeliness_details(
    operation_type: str | None = Query(None),
    timeliness_status: str | None = Query(None),
    responsibility_reason: str | None = Query(None),
    responsible_user_id: int | None = Query(None),
    actual_employer_id: int | None = Query(None),
    since: str | None = Query(None),
    until: str | None = Query(None),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    return {"items": detail_rows(
        session, user, operation_type=operation_type,
        timeliness_status=timeliness_status,
        responsibility_reason=responsibility_reason,
        responsible_user_id=responsible_user_id,
        actual_employer_id=actual_employer_id,
        since=since, until=until)}


@router.get("/timeliness/data-quality", dependencies=[Depends(_ENTERPRISE_OR_ADMIN)])
def timeliness_data_quality(user: User = Depends(current_user), session: Session = Depends(db)):
    """Facts that are real but cannot be judged yet — kept out of every rate and
    surfaced here so someone can fix them, rather than silently dropped."""
    rows = [r for r in _rows(session, user)
            if r.timeliness_status in _DATA_QUALITY_STATUSES]
    return {"items": [_serialize(r) for r in rows]}


@router.post("/timeliness/recalculate", dependencies=[Depends(_ENTERPRISE_OR_ADMIN)])
def timeliness_recalculate(
    run: bool = Query(True, description="立即处理队列；false 时只入队"),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    if user.role == "enterprise" and not is_enterprise_owner(user):
        raise HTTPException(403, "仅企业主管或平台管理员可发起重算")

    enterprise_id = user.enterprise_id if user.role == "enterprise" else None
    facts = system_facts(session, enterprise_id=enterprise_id)
    for fact in facts:
        enqueue(session, fact_id=fact.id, reason="manual")
    session.commit()

    result = process_outbox(session) if run else {"processed": 0, "failed": 0}
    session.commit()
    audit(session, user, "recalculate", "timeliness", str(enterprise_id or "all"),
          f"queued={len(facts)} processed={result['processed']} failed={result['failed']}")
    return {"queued": len(facts), **result}


@router.get("/timeliness/export", dependencies=[Depends(_ENTERPRISE_OR_ADMIN)])
def timeliness_export(
    operation_type: str | None = Query(None),
    timeliness_status: str | None = Query(None),
    responsibility_reason: str | None = Query(None),
    responsible_user_id: int | None = Query(None),
    actual_employer_id: int | None = Query(None),
    since: str | None = Query(None),
    until: str | None = Query(None),
    user: User = Depends(current_user),
    session: Session = Depends(db),
):
    """§13.4 跨企业导出必须记录筛选条件、导出人、时间、行数和文件摘要。

    The audit row is written from the same metadata the file was built with, and
    the digest is taken over the bytes actually returned — so the record proves
    what left the system, not what we meant to send. Identity numbers are masked
    by the reporting service (§15).
    """
    filters = {
        "operation_type": operation_type,
        "timeliness_status": timeliness_status,
        "responsibility_reason": responsibility_reason,
        "responsible_user_id": responsible_user_id,
        "actual_employer_id": actual_employer_id,
        "since": since,
        "until": until,
    }
    payload, meta = build_export(session, user, filters=filters)
    audit(session, user, "export", "timeliness", str(user.enterprise_id or "all"),
          json.dumps(meta, ensure_ascii=False, default=str))
    return StreamingResponse(
        iter([payload]),
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={
            "Content-Disposition": "attachment; filename=timeliness-details.xlsx",
            "X-Row-Count": str(meta["row_count"]),
            "X-File-Digest": meta["file_digest"],
        },
    )
