from typing import Literal, Optional

from pydantic import BaseModel, Field


class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0); channel: Literal["native","jsapi"] = "native"
class PaymentCallbackIn(BaseModel): order_no: str; status: Literal["paid","failed","pending"]; provider_trade_no: str = ""
# 发票抬头和纳税人识别号必须填完整才能申请（用户反馈 2026-07-29）：没有税号
# 保司/平台根本开不出正式发票，放行空值只会把问题拖到审核时才发现。
class InvoiceIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; invoice_type: Literal["增值税普通发票","增值税专用发票"] = "增值税普通发票"; amount: float = Field(gt=0); title: str = Field(min_length=1,max_length=160); tax_no: str = Field(min_length=1,max_length=40); email: str = ""
class InvoiceUpdate(BaseModel): status: Literal["pending","approved","issued","rejected"]

class InsurerAccountIn(BaseModel): label: str = ""; bank_name: str; account_no: str; account_holder: str
class InsurerAccountUpdate(BaseModel): label: Optional[str] = None; bank_name: Optional[str] = None; account_no: Optional[str] = None; account_holder: Optional[str] = None; status: Optional[Literal["active", "paused"]] = None
class InsurerAccountLinkIn(BaseModel): insurer: str = Field(min_length=1); account_id: int
