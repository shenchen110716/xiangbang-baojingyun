"""Insurer-facing settlement view (2026-07-24 design §财务管理).

Aggregates by enterprise, over the insurer's own plans only. This is a
premium-and-arrears view, not a commission ledger — internal cost/profit
fields never enter this function's output (strip_internal_pricing handles
the per-row pricing_snapshot fields that do get included).
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Enterprise, InsurancePlan, Policy
from .pricing import pricing_snapshot, strip_internal_pricing
from .serialization import amount


def insurer_settlement_summary(session: Session, insurer_id: int, user) -> dict:
    plan_ids = set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))
    if not plan_ids:
        return {"insurer_id": insurer_id, "total_active_premium": 0.0, "rows": []}

    rows = []
    total_active_premium = 0.0
    policies = session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids)).order_by(Policy.id.desc()))
    for policy in policies:
        plan = session.get(InsurancePlan, policy.plan_id)
        enterprise = session.get(Enterprise, policy.enterprise_id)
        snapshot = pricing_snapshot(plan) if plan else {}
        row = strip_internal_pricing({
            "policy_id": policy.id,
            "policy_no": policy.policy_no,
            "enterprise_name": enterprise.name if enterprise else "",
            "plan_name": plan.name if plan else "",
            "status": policy.status,
            "premium": amount(policy.premium),
            **snapshot,
        }, user)
        rows.append(row)
        if policy.status == "active":
            total_active_premium += float(policy.premium or 0)

    return {"insurer_id": insurer_id, "total_active_premium": amount(total_active_premium), "rows": rows}
