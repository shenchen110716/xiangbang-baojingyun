import json
import os
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.api_keys import IntegrationPrincipal, authenticate_integration
from ..core.business_time import BUSINESS_TIMEZONE, business_now
from ..core.db import db
from ..core.id_number import id_encrypt, id_hash, is_valid_id_number
from ..core.security import current_user
from ..models import ActualEmployer, AuditLog, EmploymentFact, EmploymentFactMatch, User
from ..services.employment_facts import serialize_fact
from ..services.employment_matching import match_person

router = APIRouter(prefix="/api", tags=["integrations"])
integration_router = router


def _parse_event_time(value):
    text = (value or "").strip()
    if not text:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d", "%Y/%m/%d"):
        try:
            return datetime.strptime(text, fmt).replace(tzinfo=BUSINESS_TIMEZONE)
        except ValueError:
            continue
    raise ValueError(f"时间格式无法识别：{text}")


def _audit_integration(session, principal, action, object_id):
    session.add(AuditLog(user_id=None, action=action, object_type="integration",
                         object_id=object_id, detail=f"key_id={principal.key_id}"))
    session.commit()


def _validate_event(session, principal, item: dict, index: int) -> dict:
    errors = []
    employer_id = item.get("actual_employer_id")
    if not employer_id:
        errors.append("actual_employer_id 必填")
    else:
        employer = session.get(ActualEmployer, employer_id)
        if not employer or employer.enterprise_id != principal.enterprise_id:
            errors.append("实际工作单位不属于该接入身份的企业")
        elif (principal.allowed_employer_ids is not None
              and employer_id not in principal.allowed_employer_ids):
            errors.append("该接入身份无权写入此实际工作单位")

    if not (item.get("person_name") or "").strip():
        errors.append("person_name 必填")
    id_number = (item.get("id_number") or "").strip()
    if not id_number:
        errors.append("id_number 必填")
    elif not is_valid_id_number(id_number):
        errors.append("身份证号格式或校验位不正确")

    hire = leave = None
    try:
        hire = _parse_event_time(item.get("actual_hire_at"))
        if hire is None:
            errors.append("actual_hire_at 必填")
    except ValueError as exc:
        errors.append(str(exc))
    try:
        leave = _parse_event_time(item.get("actual_leave_at"))
    except ValueError as exc:
        errors.append(str(exc))
    if hire and leave and leave <= hire:
        errors.append("真实离职时间必须晚于真实入职时间")

    return {"row_no": index + 1, "errors": errors}


def _ingest_event(session, principal: IntegrationPrincipal, item: dict):
    """Returns (fact, created). Idempotent on source_event_id (§7.3)."""
    # Scope is checked before field validation so an out-of-scope write is
    # refused as 403 rather than being reported as a malformed row (§7.3).
    # Scope always comes from the authenticated identity; a body enterprise_id
    # is ignored rather than honoured.
    employer_id = item.get("actual_employer_id")
    if employer_id:
        employer = session.get(ActualEmployer, employer_id)
        if employer and employer.enterprise_id == principal.enterprise_id:
            principal.assert_employer(employer_id)

    row = _validate_event(session, principal, item, 0)
    if row["errors"]:
        raise HTTPException(400, "；".join(row["errors"]))

    source_event_id = (item.get("source_event_id") or "").strip() or None
    if source_event_id:
        existing = session.scalar(select(EmploymentFact).where(
            EmploymentFact.enterprise_id == principal.enterprise_id,
            EmploymentFact.source_event_id == source_event_id))
        if existing:
            return existing, False

    id_number = item["id_number"].strip()
    hire = _parse_event_time(item.get("actual_hire_at"))
    result = match_person(
        session, enterprise_id=principal.enterprise_id,
        actual_employer_id=employer_id,
        external_employment_id=source_event_id or "",
        id_number=id_number, actual_hire_at=hire,
        external_employee_no=(item.get("external_employee_no") or "").strip())

    fact = EmploymentFact(
        enterprise_id=principal.enterprise_id,
        actual_employer_id=employer_id,
        person_id=result.person_id,
        external_employee_no=(item.get("external_employee_no") or "").strip(),
        external_employment_id=source_event_id or "",
        id_number_hash=id_hash(id_number),
        id_number_cipher=id_encrypt(id_number),
        person_name=item["person_name"].strip(),
        actual_hire_at=hire,
        actual_leave_at=_parse_event_time(item.get("actual_leave_at")),
        feedback_reported_at=_parse_event_time(item.get("feedback_reported_at")),
        source_event_id=source_event_id,
        revision_no=1,
        status="active" if result.status == "matched" else "pending_match",
        created_at=business_now())
    session.add(fact)
    session.flush()
    session.add(EmploymentFactMatch(
        employment_fact_id=fact.id, match_status=result.status,
        match_method=result.method, matched_person_id=result.person_id,
        candidate_person_id=result.person_id, confidence=result.confidence,
        reason=result.reason[:255], created_at=business_now()))
    return fact, True


@router.get("/providers/status")
def provider_status(user: User = Depends(current_user)):
    return {"mode": os.getenv("INTEGRATION_MODE", "mock"), "insurer_api": bool(os.getenv("INSURER_API_BASE_URL")), "sms": bool(os.getenv("SMS_PROVIDER_URL")), "email": bool(os.getenv("SMTP_HOST")), "payment": bool(os.getenv("PAYMENT_PROVIDER_URL"))}


# --- §7.3 外部用工事件接口 -------------------------------------------------
# Third-party reachable. Scope comes from the authenticated key, never from the
# body; source_event_id makes delivery idempotent; batch mode is all-or-nothing.

@integration_router.post("/integrations/employment-events")
async def push_employment_event(request: Request, session: Session = Depends(db)):
    principal = await authenticate_integration(session, request)
    body = json.loads(await request.body() or b"{}")
    fact, created = _ingest_event(session, principal, body)
    session.commit()
    if created:
        _audit_integration(session, principal, "employment_event", str(fact.id))
    return serialize_fact(fact)


@integration_router.post("/integrations/employment-events/batch")
async def push_employment_events(request: Request, session: Session = Depends(db)):
    principal = await authenticate_integration(session, request)
    body = json.loads(await request.body() or b"{}")
    events = body.get("events") or []
    if not isinstance(events, list) or not events:
        raise HTTPException(400, "events 不能为空")

    rows = [_validate_event(session, principal, item, index)
            for index, item in enumerate(events)]
    if any(row["errors"] for row in rows):
        # 行级错误整批拒绝，不做部分提交
        session.rollback()
        raise HTTPException(status_code=400, detail={"rows": rows})

    created = 0
    for item in events:
        _, was_created = _ingest_event(session, principal, item)
        created += 1 if was_created else 0
    session.commit()
    _audit_integration(session, principal, "employment_event_batch", str(len(events)))
    return {"created_facts": created, "total": len(events),
            "rows": [{"row_no": r["row_no"], "errors": []} for r in rows]}


@integration_router.get("/integrations/employment-events/{source_event_id}")
async def get_employment_event(source_event_id: str, request: Request,
                               session: Session = Depends(db)):
    principal = await authenticate_integration(session, request)
    fact = session.scalar(select(EmploymentFact).where(
        EmploymentFact.enterprise_id == principal.enterprise_id,
        EmploymentFact.source_event_id == source_event_id))
    if not fact:
        raise HTTPException(404, "用工事件不存在")
    principal.assert_employer(fact.actual_employer_id)
    session.commit()
    return serialize_fact(fact)
