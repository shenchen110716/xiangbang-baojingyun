"""Agent portal response schemas — allow-lists, not filtered dicts (§5.1).

AgentProductOut declares exactly the ten fields the leakage contract asserts and
nothing else. 响应 Schema 使用字段白名单，不能仅在前端隐藏内部字段: building this by
deleting keys from plan_dict would reintroduce the leak the moment a new column
is added, so it is constructed field by field.
"""
from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel


class AgentProductOut(BaseModel):
    id: int
    insurer: str
    name: str
    coverage: str
    occupation_classes: str
    billing_mode: str
    effective_mode: str
    status: str
    min_sale_price: float
    my_commission_status: str


class AgentProductListOut(BaseModel):
    items: list[AgentProductOut]


class AgentCommissionRowOut(BaseModel):
    enterprise_id: Optional[int] = None
    enterprise_name: str
    plan_id: Optional[int] = None
    plan_name: str
    insurer: str
    mode: str
    status: str
    insured_count: int
    min_sale_price: float = 0
    sale_price: float = 0
    amount: float
    unit_amount: float
    accrual_as_of: str


class AgentCommissionListOut(BaseModel):
    items: list[AgentCommissionRowOut]


class AgentCommissionSummaryOut(BaseModel):
    agent_id: int
    enterprise_count: int
    product_count: int
    insured_count: int
    estimated_total: float


class AgentBalancesOut(BaseModel):
    agent_id: int
    estimated_total: float
    pending_settlement: float
    pending_payment: float
    paid: float


class AgentStatementItemOut(BaseModel):
    id: int
    source_type: str
    plan_id: Optional[int] = None
    enterprise_id: Optional[int] = None
    amount: float
    status: str
    adjusts_item_id: Optional[int] = None
    created_at: Optional[datetime] = None


class AgentStatementOut(BaseModel):
    id: int
    statement_no: str
    period_start: date
    period_end: date
    currency: str
    total_amount: float
    status: str
    confirmed_at: Optional[datetime] = None
    created_at: Optional[datetime] = None
    items: list[AgentStatementItemOut] = []


class AgentPaymentOut(BaseModel):
    id: int
    amount: float
    channel: str
    transaction_no: str
    paid_at: Optional[datetime] = None
    voucher_url: str
    allocated_amount: float
    created_at: Optional[datetime] = None
