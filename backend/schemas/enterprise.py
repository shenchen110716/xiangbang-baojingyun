from typing import Optional

from pydantic import BaseModel, Field


class EnterpriseIn(BaseModel): name: str; kind: str = "企业"; contact: str = ""; phone: str = ""; credit_code: str = ""; agent_id: Optional[int] = None; usage_fee_daily: float = Field(default=0.1, ge=0); alert_days: int = Field(default=3, ge=3, le=7)
class EnterpriseUpdate(BaseModel): name: Optional[str] = None; kind: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None; credit_code: Optional[str] = None; agent_id: Optional[int] = None; usage_fee_daily: Optional[float] = Field(default=None, ge=0); alert_days: Optional[int] = Field(default=None, ge=3, le=7)
class RechargeIn(BaseModel): account: str = "premium"; amount: float = Field(gt=0)
