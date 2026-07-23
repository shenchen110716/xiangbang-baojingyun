from typing import Optional

from pydantic import BaseModel, Field


class PersonIn(BaseModel): enterprise_id: int; name: str; phone: str = ""; id_number: str = Field(min_length=6); occupation: str = ""; occupation_class: str = "3类"; position_id: Optional[int] = None; effective_at: Optional[str] = None; terminated_at: Optional[str] = None
class PersonUpdate(BaseModel): name: Optional[str] = None; phone: Optional[str] = None; id_number: Optional[str] = Field(default=None, min_length=6); position_id: Optional[int] = None; effective_at: Optional[str] = None; terminated_at: Optional[str] = None
class BulkPersonRow(BaseModel): name: str; id_number: str = Field(min_length=6); phone: str = ""
class BulkPersonIn(BaseModel): enterprise_id: int; position_id: int; rows: list[BulkPersonRow] = Field(min_length=1, max_length=1000)
class InsurerFlagIn(BaseModel): reason: str = ""
