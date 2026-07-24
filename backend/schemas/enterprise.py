from typing import Optional

from pydantic import BaseModel, Field


class EnterpriseIn(BaseModel): name: str; kind: str = "企业"; contact: str = ""; phone: str = ""; credit_code: str = ""; agent_id: Optional[int] = None; usage_fee_daily: float = Field(default=0.1, ge=0); alert_days: int = Field(default=3, ge=3, le=7)
class EnterpriseUpdate(BaseModel): name: Optional[str] = None; kind: Optional[str] = None; contact: Optional[str] = None; phone: Optional[str] = None; credit_code: Optional[str] = None; agent_id: Optional[int] = None; usage_fee_daily: Optional[float] = Field(default=None, ge=0); alert_days: Optional[int] = Field(default=None, ge=3, le=7)
class RechargeIn(BaseModel): account: str = "premium"; amount: float = Field(gt=0)
class EnterpriseApplyIn(BaseModel):
    enterprise_name: str = Field(min_length=1, max_length=160)
    credit_code: str = Field(default="", max_length=40)
    contact: str = Field(default="", max_length=80)
    phone: str = Field(default="", max_length=30)
    username: str = Field(min_length=3, max_length=80)
    password: str = Field(min_length=6, max_length=128)
    # 蜜罐字段：前端用 CSS 藏起来，真人看不到也不会填；简单爬虫脚本常见的
    # "把所有 input 都填一遍"策略会填上这个。非空即判定为机器人，见 routers/enterprises.py。
    website: str = Field(default="", max_length=200)
