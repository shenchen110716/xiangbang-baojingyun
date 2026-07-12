from typing import Literal, Optional

from pydantic import BaseModel, Field


class PlanTierIn(BaseModel): plan_id: int; occupation_class: Literal["1-3类","4类","5类","超5类"]; price: float = Field(ge=0); coverage: str = ""
class PlanIn(BaseModel): insurer: str; insurer_email: str = ""; name: str; coverage: str = ""; occupation_classes: str = "1-4类"; price: float = Field(ge=0); commission_rate: float = Field(default=0, ge=0, le=1); profit_amount: float = Field(default=0,ge=0); payment_mode: str = "企业直投"; billing_mode: Literal["monthly","daily"] = "monthly"; effective_mode: Literal["next_day","immediate"] = "next_day"
class PlanUpdate(BaseModel): insurer: Optional[str] = None; insurer_email: Optional[str] = None; name: Optional[str] = None; coverage: Optional[str] = None; occupation_classes: Optional[str] = None; price: Optional[float] = Field(default=None, ge=0); commission_rate: Optional[float] = Field(default=None, ge=0, le=1); profit_amount: Optional[float] = Field(default=None,ge=0); payment_mode: Optional[str] = None; billing_mode: Optional[Literal["monthly","daily"]] = None; effective_mode: Optional[Literal["next_day","immediate"]] = None
