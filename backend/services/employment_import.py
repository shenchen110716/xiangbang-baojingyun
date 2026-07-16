"""Two-phase atomic employment fact import (v4.2 §7.1, §7.2, §6.1).

Preview reports without writing; confirm re-derives the same report inside one
transaction and writes all-or-nothing. The confirm token is bound to one
uploader, one file hash and one preview version, and only its digest is stored,
so a leaked batch row cannot be used to confirm.

Rows outside the caller's employer scope are blocking errors, never silent
skips: a silently dropped row would look like a successful import and leave a
hole in the fact base that Phase 3 would read as "no employment".
"""
import hashlib
import secrets
from pathlib import Path
from datetime import datetime
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from ..core.business_time import BUSINESS_TIMEZONE, business_now
from ..core.config import ROOT
from ..core.id_number import (
    decrypt_bytes as id_decrypt_bytes,
    encrypt_bytes as id_encrypt_bytes,
    id_encrypt,
    id_hash,
    is_valid_id_number,
    mask_id_number,
)
from ..models import (
    ActualEmployer,
    EmploymentFact,
    EmploymentFactMatch,
    EmploymentFeedbackBatch,
    User,
)
from .employer_scopes import allowed_employer_ids
from .employment_matching import match_person
from .spreadsheet import read_import_rows

# §7.1 标准模板字段，顺序固定。
TEMPLATE_HEADER = [
    "实际工作单位", "外部员工编号", "姓名", "身份证号",
    "真实入职时间", "真实离职时间", "反馈时间", "外部用工记录号", "备注",
]
_EMPLOYER, _EMP_NO, _NAME, _ID, _HIRE, _LEAVE, _FEEDBACK, _SOURCE, _REMARK = range(9)

# Outside the web root and never statically mounted, like position videos.
_UPLOAD_ROOT = ROOT / "uploads" / "employment"


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _parse_time(value: str) -> Optional[datetime]:
    """Business-timezone in, UTC out (§6.2)."""
    text = (value or "").strip()
    if not text:
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d", "%Y/%m/%d"):
        try:
            naive = datetime.strptime(text, fmt)
        except ValueError:
            continue
        return naive.replace(tzinfo=BUSINESS_TIMEZONE)
    raise ValueError(f"时间格式无法识别：{text}")


def _cell(row: list[str], index: int) -> str:
    return (row[index] if index < len(row) else "").strip()


def _store_source_file(batch_id: int, file_hash: str, content: bytes) -> str:
    """Encrypted, outside the web root, name derived from the content hash."""
    _UPLOAD_ROOT.mkdir(parents=True, exist_ok=True)
    relative = f"{batch_id}-{file_hash[:16]}.enc"
    (_UPLOAD_ROOT / relative).write_bytes(id_encrypt_bytes(content))
    return relative


def _employers_by_name(session: Session, enterprise_id: int) -> dict[str, ActualEmployer]:
    rows = session.scalars(
        select(ActualEmployer).where(ActualEmployer.enterprise_id == enterprise_id))
    return {e.name.strip(): e for e in rows}


def _build_report(session: Session, user: User, *, enterprise_id: int,
                  raw_rows: list[list[str]]) -> list[dict]:
    """Row-level report. Pure read: never writes a fact."""
    if not raw_rows:
        raise HTTPException(400, "导入文件为空，请使用标准模板")
    header = [c.strip() for c in raw_rows[0]]
    if header[:len(TEMPLATE_HEADER)] != TEMPLATE_HEADER:
        raise HTTPException(400, f"模板表头不正确，请使用标准模板：{'、'.join(TEMPLATE_HEADER)}")

    employers = _employers_by_name(session, enterprise_id)
    allowed = allowed_employer_ids(session, user)
    seen_source_ids: set[str] = set()
    report: list[dict] = []

    for offset, raw in enumerate(raw_rows[1:], start=2):
        errors: list[str] = []
        warnings: list[str] = []

        employer_name = _cell(raw, _EMPLOYER)
        name = _cell(raw, _NAME)
        id_number = _cell(raw, _ID)
        source_event_id = _cell(raw, _SOURCE)

        employer = employers.get(employer_name)
        if not employer_name:
            errors.append("实际工作单位必填")
        elif not employer:
            errors.append(f"实际工作单位不存在：{employer_name}")
        elif allowed is not None and employer.id not in allowed:
            # 越权单位整行阻断，绝不静默跳过
            errors.append(f"未获授权操作该实际工作单位：{employer_name}")

        if not name:
            errors.append("姓名必填")
        if not id_number:
            errors.append("身份证号必填")
        elif not is_valid_id_number(id_number):
            errors.append("身份证号格式或校验位不正确")

        hire = leave = feedback = None
        try:
            hire = _parse_time(_cell(raw, _HIRE))
            if hire is None:
                errors.append("真实入职时间必填")
        except ValueError as exc:
            errors.append(str(exc))
        try:
            leave = _parse_time(_cell(raw, _LEAVE))
        except ValueError as exc:
            errors.append(str(exc))
        try:
            feedback = _parse_time(_cell(raw, _FEEDBACK))
        except ValueError as exc:
            errors.append(str(exc))

        if hire and leave and leave <= hire:
            errors.append("真实离职时间必须晚于真实入职时间")

        if source_event_id:
            if source_event_id in seen_source_ids:
                errors.append(f"文件内外部用工记录号重复：{source_event_id}")
            seen_source_ids.add(source_event_id)

        match = None
        if not errors and employer and hire:
            match = match_person(
                session,
                enterprise_id=enterprise_id,
                actual_employer_id=employer.id,
                external_employment_id=source_event_id,
                id_number=id_number,
                actual_hire_at=hire,
                external_employee_no=_cell(raw, _EMP_NO),
            )
            if match.status in ("pending", "ambiguous"):
                # Not blocking: the fact is still real and worth recording, but
                # it stays out of published metrics until a human binds it (§20.6).
                warnings.append(match.reason)

        report.append({
            "row_no": offset,
            "errors": errors,
            "warnings": warnings,
            "masked_id": mask_id_number(id_number),
            "person_name": name,
            "actual_employer_id": employer.id if employer else None,
            "actual_employer": employer_name,
            "external_employee_no": _cell(raw, _EMP_NO),
            "source_event_id": source_event_id,
            "match_status": match.status if match else "",
            "match_method": match.method if match else "",
            "_person_id": match.person_id if match else None,
            "_confidence": match.confidence if match else 0.0,
            "_reason": match.reason if match else "",
            "_hire": hire,
            "_leave": leave,
            "_feedback": feedback,
            "_id_number": id_number,
        })
    return report


def _public_row(row: dict) -> dict:
    """Strip internal carriers; plaintext ID never leaves this module (§6.4)."""
    return {k: v for k, v in row.items() if not k.startswith("_")}


def preview_import(session: Session, user: User, *, enterprise_id: int,
                   filename: str, content: bytes) -> dict:
    if user.role == "enterprise" and user.enterprise_id != enterprise_id:
        raise HTTPException(403, "无权导入该投保单位数据")

    raw_rows = read_import_rows(content, filename)
    report = _build_report(session, user, enterprise_id=enterprise_id, raw_rows=raw_rows)

    file_hash = hashlib.sha256(content).hexdigest()
    token = secrets.token_urlsafe(32)
    valid = sum(1 for r in report if not r["errors"])

    batch = session.scalar(
        select(EmploymentFeedbackBatch).where(
            EmploymentFeedbackBatch.enterprise_id == enterprise_id,
            EmploymentFeedbackBatch.source_file_hash == file_hash,
            EmploymentFeedbackBatch.source_type == "manual_import",
            EmploymentFeedbackBatch.status.in_(("uploaded", "previewed")),
        ))
    if batch is None:
        batch = EmploymentFeedbackBatch(
            enterprise_id=enterprise_id, source_type="manual_import",
            source_filename=filename or "", source_file_hash=file_hash,
            created_at=business_now(), preview_version=0)
        session.add(batch)

    # Re-previewing bumps the version, which invalidates any token handed out
    # for the previous preview: confirming a report the user never saw would
    # defeat the point of the two-phase flow.
    batch.preview_version += 1
    batch.status = "previewed"
    batch.imported_by = user.id
    batch.total_rows = len(report)
    batch.valid_rows = valid
    batch.invalid_rows = len(report) - valid
    batch.confirm_token_digest = _digest(f"{token}:{batch.preview_version}")
    batch.updated_at = business_now()
    session.flush()

    # 原始上传文件私有、加密留存（§6.4），确认时据此重算，不信任客户端回传。
    batch.source_file_path = _store_source_file(batch.id, file_hash, content)
    session.flush()

    return {
        "batch_id": batch.id,
        "confirm_token": token,
        "preview_version": batch.preview_version,
        "total_rows": batch.total_rows,
        "valid_rows": batch.valid_rows,
        "invalid_rows": batch.invalid_rows,
        "rows": [_public_row(r) for r in report],
    }


def _write_fact(session: Session, user: User, batch: EmploymentFeedbackBatch,
                row: dict) -> EmploymentFact:
    fact = EmploymentFact(
        enterprise_id=batch.enterprise_id,
        actual_employer_id=row["actual_employer_id"],
        person_id=row["_person_id"],
        external_employee_no=row["external_employee_no"],
        external_employment_id=row["source_event_id"],
        id_number_hash=id_hash(row["_id_number"]) if row["_id_number"] else "",
        id_number_cipher=id_encrypt(row["_id_number"]) if row["_id_number"] else "",
        person_name=row["person_name"],
        actual_hire_at=row["_hire"],
        actual_leave_at=row["_leave"],
        feedback_reported_at=row["_feedback"],
        batch_id=batch.id,
        source_event_id=row["source_event_id"] or None,
        revision_no=1,
        # 未匹配或有歧义的事实不得进入正式指标（§20.6）
        status="active" if row["match_status"] == "matched" else "pending_match",
        created_by=user.id,
        created_at=business_now(),
    )
    session.add(fact)
    session.flush()
    session.add(EmploymentFactMatch(
        employment_fact_id=fact.id,
        match_status=row["match_status"] or "pending",
        match_method=row["match_method"] or "manual",
        matched_person_id=row["_person_id"] if row["match_status"] == "matched" else None,
        candidate_person_id=row["_person_id"],
        confidence=row["_confidence"],
        reason=row["_reason"][:255],
        created_at=business_now(),
    ))
    return fact


def confirm_import(session: Session, user: User, *, batch_id: int,
                   confirm_token: str) -> dict:
    batch = session.get(EmploymentFeedbackBatch, batch_id)
    if not batch:
        raise HTTPException(404, "导入批次不存在")
    if user.role == "enterprise" and batch.enterprise_id != user.enterprise_id:
        raise HTTPException(403, "无权确认该批次")
    if batch.imported_by != user.id:
        raise HTTPException(403, "只能由上传人确认该批次")

    # 条件更新原子抢占确认权（沿用 pending_terminations 已验证的写法）：并发的
    # 第二个请求 rowcount 为 0，不会重复写入。
    claimed = session.execute(
        update(EmploymentFeedbackBatch)
        .where(EmploymentFeedbackBatch.id == batch_id,
               EmploymentFeedbackBatch.status == "previewed",
               EmploymentFeedbackBatch.confirm_token_digest
               == _digest(f"{confirm_token}:{batch.preview_version}"))
        .values(status="confirmed")).rowcount
    if claimed != 1:
        raise HTTPException(409, "该批次已确认或令牌已失效，请重新预览")

    raw_rows = _stored_rows(session, batch)
    report = _build_report(session, user, enterprise_id=batch.enterprise_id,
                           raw_rows=raw_rows)
    if any(r["errors"] for r in report):
        # Rolls back the claim above, so the token stays usable after fixing.
        raise HTTPException(400, "仍有阻断错误，请全部处理后再确认")

    created = [_write_fact(session, user, batch, row) for row in report]

    batch.confirm_token_digest = None          # 一次性
    batch.imported_at = business_now()
    batch.updated_at = business_now()
    # Terminal for this phase; Phase 3 advances it to 'completed' after
    # recalculating timeliness.
    batch.status = "imported_pending_calculation"
    session.flush()
    return {"batch_id": batch.id, "status": batch.status, "created_facts": len(created)}


def _stored_rows(session: Session, batch: EmploymentFeedbackBatch) -> list[list[str]]:
    """Re-read the original upload so confirm never trusts client-supplied rows."""
    if not batch.source_file_path:
        raise HTTPException(409, "原始导入文件已不可用，请重新上传预览")
    path = _UPLOAD_ROOT / batch.source_file_path
    try:
        content = id_decrypt_bytes(path.read_bytes())
    except FileNotFoundError:
        raise HTTPException(409, "原始导入文件已不可用，请重新上传预览") from None
    return read_import_rows(content, batch.source_filename or "a.xlsx")
