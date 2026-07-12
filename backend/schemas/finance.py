from typing import Literal

from pydantic import BaseModel, Field


class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0)
class PaymentCallbackIn(BaseModel): order_no: str; status: Literal["paid","failed","pending"]; provider_trade_no: str = ""
class InvoiceIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0); title: str = Field(min_length=1,max_length=160); tax_no: str = ""; email: str = ""
class InvoiceUpdate(BaseModel): status: Literal["pending","approved","issued","rejected"]
