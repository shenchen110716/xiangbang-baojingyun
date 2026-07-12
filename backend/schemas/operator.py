from typing import Optional

from pydantic import BaseModel, Field


class OperatorIn(BaseModel): username: str = Field(min_length=3, max_length=80); password: str = Field(min_length=6, max_length=128); name: str = Field(min_length=1, max_length=80); phone: str = ""; enterprise_id: Optional[int] = None
class OperatorUpdate(BaseModel): name: Optional[str] = Field(default=None, min_length=1, max_length=80); phone: Optional[str] = None; password: Optional[str] = Field(default=None, min_length=6, max_length=128); active: Optional[bool] = None
