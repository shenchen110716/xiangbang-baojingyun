from typing import Literal, Optional

from pydantic import BaseModel, Field


class PaymentIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0)
class PaymentCallbackIn(BaseModel): order_no: str; status: Literal["paid","failed","pending"]; provider_trade_no: str = ""
class InvoiceIn(BaseModel): enterprise_id: int; account: Literal["premium","usage"] = "premium"; amount: float = Field(gt=0); title: str = Field(min_length=1,max_length=160); tax_no: str = ""; email: str = ""
class InvoiceUpdate(BaseModel): status: Literal["pending","approved","issued","rejected"]

class InsurerAccountIn(BaseModel): label: str = ""; bank_name: str; account_no: str; account_holder: str
class InsurerAccountUpdate(BaseModel): label: Optional[str] = None; bank_name: Optional[str] = None; account_no: Optional[str] = None; account_holder: Optional[str] = None; status: Optional[Literal["active", "paused"]] = None
class InsurerAccountLinkIn(BaseModel): insurer: str = Field(min_length=1); account_id: int
