from typing import Optional

from pydantic import BaseModel, Field


# enroll=False：只收名单不参保（status='draft' 未参保），不过使用费门禁、不记参保
# 操作、不产生任何费用；之后用 POST /insured/batch-enroll 批量参保，门禁挪到那一步
# （2026-09-20 用户反馈：添加和参保分两步，参保时再判断费用，体验更好）。
class PersonIn(BaseModel): enterprise_id: int; name: str; phone: str = ""; id_number: str = Field(min_length=6); occupation: str = ""; occupation_class: str = "3类"; position_id: Optional[int] = None; effective_at: Optional[str] = None; terminated_at: Optional[str] = None; enroll: bool = True
class BatchEnrollIn(BaseModel): ids: list[int] = Field(min_length=1, max_length=1000)
class PersonUpdate(BaseModel): name: Optional[str] = None; phone: Optional[str] = None; id_number: Optional[str] = Field(default=None, min_length=6); position_id: Optional[int] = None; effective_at: Optional[str] = None; terminated_at: Optional[str] = None
class BulkPersonRow(BaseModel): name: str; id_number: str = Field(min_length=6); phone: str = ""
class BulkPersonIn(BaseModel): enterprise_id: int; position_id: int; rows: list[BulkPersonRow] = Field(min_length=1, max_length=1000)
class InsurerFlagIn(BaseModel): reason: str = ""
