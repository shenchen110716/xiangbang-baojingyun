from typing import Literal, Optional

from pydantic import BaseModel, Field


class AgentIn(BaseModel): username: str; password: str; name: str; phone: str = ""
class CommissionIn(BaseModel): agent_id: int; enterprise_id: int; plan_id: int; rate: float = Field(default=0, ge=0, le=1); mode: Literal["rebate","price","markup"] = "rebate"; markup_amount: float = Field(default=0, ge=0); sale_price: float = Field(default=0, ge=0)
class CommissionUpdate(BaseModel): rate: Optional[float] = Field(default=None, ge=0, le=1); mode: Optional[Literal["rebate","price","markup"]] = None; markup_amount: Optional[float] = Field(default=None, ge=0); sale_price: Optional[float] = Field(default=None, ge=0); status: Optional[str] = None
