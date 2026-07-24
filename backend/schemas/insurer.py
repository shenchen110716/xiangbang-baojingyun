from typing import Optional

from pydantic import BaseModel, Field


class InsurerIn(BaseModel):
    name: str = Field(min_length=1)
    contact: str = ""
    phone: str = ""
    credit_code: str = ""
    email: str = ""
    address: str = ""


class InsurerUpdate(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    phone: Optional[str] = None
    credit_code: Optional[str] = None
    email: Optional[str] = None
    address: Optional[str] = None


class InsurerEditReviewIn(BaseModel):
    approve: bool
    reject_reason: str = ""


class InsurerMergeIn(BaseModel):
    source_ids: list[int] = Field(min_length=1)
    target_id: int


class InsurerProfileIn(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    phone: Optional[str] = None
    credit_code: Optional[str] = None
    email: Optional[str] = None
    address: Optional[str] = None


class InsurerAccountIn(BaseModel):
    username: str = Field(min_length=1)
    password: str = Field(min_length=6, max_length=128)
    name: str = ""


class InsurerAccountPasswordResetIn(BaseModel):
    password: str = Field(min_length=6, max_length=128)
