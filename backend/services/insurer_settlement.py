"""Insurer-facing settlement view (2026-07-24 design §财务管理).

Aggregates by enterprise, over the insurer's own plans only. This is a
premium-and-arrears view, not a commission ledger — internal cost/profit
fields never enter this function's output (strip_internal_pricing handles
the per-row pricing_snapshot fields that do get included).
"""
from calendar import monthrange
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..models import Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember
from .accruals import billable_date_range, period_amount
from .pricing import plan_price_for_class, pricing_snapshot, strip_internal_pricing
from .serialization import amount


def _policy_active_premium(session: Session, policy: Policy, plan: InsurancePlan | None) -> tuple[float, int]:
    """当前在保人数 × 各自结算价（policy_floor_price，按人的职业类别分档定价），
    不是 Policy.premium 那个静态列——那一列在真实数据里经常没有被填过（新建保单
    时不强制录入，实际保费按人动态算），直接拿它当"在保保费合计"会一直显示 0。
    这里按 policy_dict() 同一套"当前在保人 × 结算价"口径重算一次。"""
    if not plan:
        return 0.0, 0
    members = session.scalars(select(PolicyMember).where(PolicyMember.policy_id == policy.id, PolicyMember.status == "active"))
    total = 0.0
    count = 0
    for member in members:
        person = session.get(InsuredPerson, member.person_id)
        if not person:
            continue
        snapshot = pricing_snapshot(plan, base_price=plan_price_for_class(session, plan, person.occupation_class))
        total += float(snapshot.get("policy_floor_price") or 0)
        count += 1
    return total, count


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
        policy_premium, insured_count = _policy_active_premium(session, policy, plan)
        row = strip_internal_pricing({
            "policy_id": policy.id,
            "policy_no": policy.policy_no,
            "enterprise_name": enterprise.name if enterprise else "",
            "plan_name": plan.name if plan else "",
            "status": policy.status,
            "insured_count": insured_count,
            "premium": amount(policy_premium),
            **snapshot,
        }, user)
        rows.append(row)
        if policy.status == "active":
            total_active_premium += policy_premium

    return {"insurer_id": insurer_id, "total_active_premium": amount(total_active_premium), "rows": rows}


def _month_bounds(year: int, month: int) -> tuple[date, date]:
    last_day = monthrange(year, month)[1]
    return date(year, month, 1), date(year, month, last_day)


def _shift_month(year: int, month: int, delta: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + delta
    return total // 12, total % 12 + 1


def insurer_monthly_premium_rows(session: Session, insurer_id: int, year: int, month: int) -> list[dict]:
    """该保司名下、指定自然月里每个在保人的应收保费明细。

    单价用结算价（policy_floor_price，保司实际到手的价格），不是客户端 sale_price，
    和 insurer_settlement_summary()、strip_internal_pricing 的保司口径保持一致。
    按人按天/按月折算复用 policy_dict() 同一套 billable_date_range/period_amount
    机制，只是把区间从"当前自然月"参数化成任意指定月份。
    """
    plan_ids = set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))
    if not plan_ids:
        return []
    month_start, month_end = _month_bounds(year, month)
    cutoff = min(month_end, business_today())
    if cutoff < month_start:
        return []
    rows: list[dict] = []
    policies = list(session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids))))
    for policy in policies:
        plan = session.get(InsurancePlan, policy.plan_id)
        if not plan:
            continue
        enterprise = session.get(Enterprise, policy.enterprise_id)
        members = session.scalars(select(PolicyMember).where(PolicyMember.policy_id == policy.id))
        for member in members:
            billable = billable_date_range(member, month_start, cutoff)
            if billable is None:
                continue
            start, end = billable
            person = session.get(InsuredPerson, member.person_id)
            if not person:
                continue
            ratio = period_amount(1.0, plan.billing_mode, start, end)
            snapshot = pricing_snapshot(plan, base_price=plan_price_for_class(session, plan, person.occupation_class))
            unit_price = float(snapshot.get("policy_floor_price") or 0)
            rows.append({
                "person_id": person.id, "person_name": person.name, "id_number": person.id_number,
                "enterprise_name": enterprise.name if enterprise else "",
                "policy_no": policy.policy_no, "billable_ratio": round(ratio, 4),
                "unit_price": amount(unit_price), "amount": amount(unit_price * ratio),
            })
    return rows


def insurer_monthly_premium_summary(session: Session, insurer_id: int, months: int = 12) -> list[dict]:
    """最近 months 个自然月（含当月，倒序）的应收总保费汇总。"""
    today = business_today()
    result = []
    for i in range(months):
        y, m = _shift_month(today.year, today.month, -i)
        rows = insurer_monthly_premium_rows(session, insurer_id, y, m)
        result.append({
            "month": f"{y:04d}-{m:02d}",
            "total_premium": amount(sum(row["amount"] for row in rows)),
            "insured_count": len({row["person_id"] for row in rows}),
        })
    return result
