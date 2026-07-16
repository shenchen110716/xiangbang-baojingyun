from datetime import datetime
from typing import Literal

from pydantic import BaseModel


class EmployerScopeIn(BaseModel):
    user_id: int
    actual_employer_id: int
    responsibility_type: Literal["primary", "collaborator"] = "collaborator"


class PrimaryManagerIn(BaseModel):
    user_id: int


class EmployerScopeOut(BaseModel):
    id: int
    user_id: int
    user_name: str
    enterprise_id: int
    actual_employer_id: int
    actual_employer_name: str
    responsibility_type: Literal["primary", "collaborator"]
    assigned_at: datetime
    revoked_at: datetime | None
    status: Literal["active", "revoked"]
