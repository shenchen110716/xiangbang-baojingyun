"""One query behind the agent portal's list, summary and export (§14.4).

§14.4 requires 查询、汇总和导出共用同一过滤服务 — three divergent queries are how
a total stops matching its own detail rows. So `commission_rows` is the single
source, and the summary and export are derived from exactly the rows it returns.

Every row is an allow-list: only fields a salesperson may see (§5.1). The
underlying agent_commission_rows carries internal cost figures (settlement
price, profit) from pricing_snapshot; those are dropped here field by field, not
by blacklisting keys, so a new internal field cannot leak by default.
"""
from dataclasses import dataclass
from typing import Optional

from sqlalchemy.orm import Session

from .commissions import agent_commission_rows

# 业务员佣金明细行的字段白名单（§5.1）。
_ROW_FIELDS = (
    "enterprise_id", "enterprise_name", "plan_id", "plan_name", "insurer",
    "mode", "status", "insured_count",
    "agent_commission_unit", "agent_commission_total", "accrual_as_of",
)


@dataclass(frozen=True)
class CommissionFilters:
    enterprise_id: Optional[int] = None
    insurer: Optional[str] = None
    plan_id: Optional[int] = None


def _safe_row(row: dict) -> dict:
    """Allow-list projection: only salesperson-safe fields leave this module."""
    return {
        "enterprise_id": row.get("enterprise_id"),
        "enterprise_name": row.get("enterprise_name", ""),
        "plan_id": row.get("plan_id"),
        "plan_name": row.get("plan_name", ""),
        "insurer": row.get("insurer", ""),
        "mode": row.get("mode", ""),
        "status": row.get("status", ""),
        "insured_count": row.get("insured_count", 0),
        # 平台最低价（业务员报价下限）与销售价（业务员自己的定价），供报价参考。
        "min_sale_price": round(float(row.get("minimum_sale_price") or 0), 2),
        "sale_price": round(float(row.get("sale_price") or 0), 2),
        # 单笔与累计业务员佣金：这是业务员自己的收入，允许可见。
        "amount": round(float(row.get("agent_commission_total") or 0), 2),
        "unit_amount": round(float(row.get("agent_commission_unit") or 0), 2),
        "accrual_as_of": row.get("accrual_as_of", ""),
    }


def commission_rows(session: Session, agent_id: int,
                    filters: CommissionFilters = CommissionFilters()) -> list[dict]:
    rows = []
    for raw in agent_commission_rows(session, agent_id):
        if filters.enterprise_id is not None and raw.get("enterprise_id") != filters.enterprise_id:
            continue
        if filters.insurer and raw.get("insurer") != filters.insurer:
            continue
        if filters.plan_id is not None and raw.get("plan_id") != filters.plan_id:
            continue
        rows.append(_safe_row(raw))
    return rows


def commission_summary(session: Session, agent_id: int,
                       filters: CommissionFilters = CommissionFilters()) -> dict:
    """Derived from exactly the rows commission_rows returns, so the total can
    never drift from the detail (§14.4)."""
    rows = commission_rows(session, agent_id, filters)
    active = [r for r in rows if r["status"] == "active"]
    return {
        "agent_id": agent_id,
        "enterprise_count": len({r["enterprise_id"] for r in active}),
        "product_count": len(active),
        "insured_count": sum(r["insured_count"] for r in active),
        "estimated_total": round(sum(r["amount"] for r in rows), 2),
    }
