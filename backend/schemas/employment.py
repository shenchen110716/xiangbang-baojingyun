"""Employment fact API shapes (v4.2 §14.2).

`FactOut.id_number` is masked-only by construction: the service never emits
plaintext, and this schema exists so a future column addition cannot leak one
by accident (§6.4).
"""
from datetime import datetime
from typing import Optional

from pydantic import BaseModel


class ImportRowOut(BaseModel):
    row_no: int
    errors: list[str]
    warnings: list[str]
    masked_id: str
    person_name: str
    actual_employer_id: Optional[int] = None
    actual_employer: str
    external_employee_no: str
    source_event_id: str
    match_status: str
    match_method: str


class ImportPreviewOut(BaseModel):
    batch_id: int
    confirm_token: str
    preview_version: int
    total_rows: int
    valid_rows: int
    invalid_rows: int
    rows: list[ImportRowOut]


class ImportConfirmIn(BaseModel):
    batch_id: int
    confirm_token: str


class ImportConfirmOut(BaseModel):
    batch_id: int
    status: str
    created_facts: int


class BatchOut(BaseModel):
    id: int
    enterprise_id: int
    source_type: str
    source_filename: str
    status: str
    preview_version: int
    total_rows: int
    valid_rows: int
    invalid_rows: int
    imported_by: Optional[int] = None
    imported_at: Optional[datetime] = None
    created_at: Optional[datetime] = None


class FactOut(BaseModel):
    id: int
    enterprise_id: int
    actual_employer_id: int
    person_id: Optional[int] = None
    person_name: str
    id_number: str          # 永远是脱敏值，绝不返回原文（§6.4）
    external_employee_no: str
    external_employment_id: str
    actual_hire_at: Optional[datetime] = None
    actual_leave_at: Optional[datetime] = None
    feedback_reported_at: Optional[datetime] = None
    revision_no: int
    previous_version_id: Optional[int] = None
    status: str
    batch_id: Optional[int] = None
    created_at: Optional[datetime] = None


class FactListOut(BaseModel):
    items: list[FactOut]


class FactCorrectIn(BaseModel):
    actual_hire_at: Optional[datetime] = None
    actual_leave_at: Optional[datetime] = None
    reason: str


class ManualMatchIn(BaseModel):
    person_id: int
    reason: str = ""
