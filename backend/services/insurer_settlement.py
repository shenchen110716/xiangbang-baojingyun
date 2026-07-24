"""Insurer-facing settlement view (2026-07-24 design §财务管理).

Aggregates by enterprise, over the insurer's own plans only. This is a
premium-and-arrears view, not a commission ledger — internal cost/profit
fields never enter this function's output (strip_internal_pricing handles
the per-row pricing_snapshot fields that do get included).
"""
import re
from calendar import monthrange
from datetime import date, datetime, timezone

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..core.business_time import business_today
from ..models import Enterprise, InsurancePlan, InsuredPerson, InsurerMonthlySettlement, Policy, PolicyMember, User
from .accruals import billable_date_range, period_amount
from .pricing import plan_price_for_class, pricing_snapshot, strip_internal_pricing
from .serialization import amount, serialize

_MONTH_PATTERN = re.compile(r"^(\d{4})-(0[1-9]|1[0-2])$")


def parse_insurer_month(month: str) -> tuple[int, int, str]:
    """严格正则而不是 int() 直接转换：int() 会悄悄吃掉首尾空白/控制字符（比如
    "07\\n" 也能转成 7），一旦校验通过又把原始字符串继续往下传，这些字符就可能
    被裸拼进文件名/HTTP 响应头。返回值的第三项是用校验后的 (year, month) 重新
    拼出的规范化字符串，调用方后续所有拼接一律用这个，不用调用方传入的原始值。
    这里是唯一实现，insurers.py（管理端标记结算）和 insurer_portal.py（保司端
    查询）都复用这一份，不要各自重新写一遍正则。"""
    match = _MONTH_PATTERN.match(month)
    if not match: raise HTTPException(400, "月份格式应为 yyyy-MM")
    year, month_num = int(match.group(1)), int(match.group(2))
    if not (2000 <= year <= 2100): raise HTTPException(400, "月份年份超出支持范围")
    return year, month_num, f"{year:04d}-{month_num:02d}"


def _policy_cumulative_premium(session: Session, policy: Policy, plan: InsurancePlan | None) -> tuple[float, int]:
    """该保单从有史以来每个在保人各自生效日起，累计到今天的应收保费（结算价
    policy_floor_price 口径，按天/按月折算）——不是只看"现在还在保的人"，已经
    停保的人在他们停保前的在保区间同样要计入累计总额，口径参照
    policy_premium_consumed()（backend/services/policies.py），只是把客户端
    sale_price 换成保司结算价 policy_floor_price。"""
    if not plan:
        return 0.0, 0
    today = business_today()
    total = 0.0
    person_ids: set[int] = set()
    for member in session.scalars(select(PolicyMember).where(PolicyMember.policy_id == policy.id)):
        billable = billable_date_range(member, member.effective_at.date(), today)
        if billable is None:
            continue
        start, end = billable
        person = session.get(InsuredPerson, member.person_id)
        if not person:
            continue
        snapshot = pricing_snapshot(plan, base_price=plan_price_for_class(session, plan, person.occupation_class))
        unit_price = float(snapshot.get("policy_floor_price") or 0)
        total += period_amount(unit_price, plan.billing_mode, start, end)
        person_ids.add(person.id)
    return amount(total), len(person_ids)


def insurer_settlement_summary(session: Session, insurer_id: int, user) -> dict:
    plan_ids = set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))
    if not plan_ids:
        return {"insurer_id": insurer_id, "total_cumulative_premium": 0.0, "rows": []}

    rows = []
    total_cumulative_premium = 0.0
    policies = session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids)).order_by(Policy.id.desc()))
    for policy in policies:
        plan = session.get(InsurancePlan, policy.plan_id)
        enterprise = session.get(Enterprise, policy.enterprise_id)
        snapshot = pricing_snapshot(plan) if plan else {}
        policy_premium, insured_count = _policy_cumulative_premium(session, policy, plan)
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
        # 累计口径不看 policy.status——保单本身状态变了不影响它历史上已经产生过
        # 的保费，这笔钱已经实实在在地"发生"过，不能因为保单后来被停用就从
        # 累计总额里消失。
        total_cumulative_premium += policy_premium

    return {"insurer_id": insurer_id, "total_cumulative_premium": amount(total_cumulative_premium), "rows": rows}


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
            row_amount = amount(unit_price * ratio)
            # 金额为 0（或因为浮点误差落到 0 以下）的记录对保司没有实际意义，
            # 只会把明细列表刷得很长，按需求过滤掉，不展示、不导出。
            if row_amount <= 0:
                continue
            rows.append({
                "person_id": person.id, "person_name": person.name, "id_number": person.id_number,
                "enterprise_name": enterprise.name if enterprise else "",
                "policy_no": policy.policy_no,
                "effective_at": member.effective_at.date().isoformat(),
                "terminated_at": member.terminated_at.date().isoformat() if member.terminated_at else None,
                "billable_days": (end - start).days + 1,
                "billable_ratio": round(ratio, 4),
                "unit_price": amount(unit_price), "amount": row_amount,
            })
    return rows


def _settlement_record(session: Session, insurer_id: int, month: str) -> InsurerMonthlySettlement | None:
    return session.scalar(select(InsurerMonthlySettlement).where(
        InsurerMonthlySettlement.insurer_id == insurer_id, InsurerMonthlySettlement.month == month))


def insurer_monthly_premium_summary(session: Session, insurer_id: int, months: int = 12) -> list[dict]:
    """最近 months 个自然月（含当月，倒序）的应收总保费汇总，附带该月是否已经
    平台标记结算、结算时间——纯记账标记，不影响这里的金额计算本身。当月保费
    合计为 0 的月份（该保司在这个月完全没有产生保费）直接不列出，和明细页
    "只列保费大于 0 的记录"（insurer_monthly_premium_rows）保持同一口径，不
    然列表里会堆一长串没有实际意义的 0 记录。"""
    today = business_today()
    result = []
    for i in range(months):
        y, m = _shift_month(today.year, today.month, -i)
        month_str = f"{y:04d}-{m:02d}"
        rows = insurer_monthly_premium_rows(session, insurer_id, y, m)
        total_premium = amount(sum(row["amount"] for row in rows))
        if total_premium <= 0:
            continue
        settlement = _settlement_record(session, insurer_id, month_str)
        result.append({
            "month": month_str,
            "total_premium": total_premium,
            "insured_count": len({row["person_id"] for row in rows}),
            "settled": settlement is not None,
            "settled_at": settlement.settled_at if settlement else None,
        })
    return result


def mark_insurer_month_settled(session: Session, insurer_id: int, month: str, admin: User, note: str = "") -> dict:
    """标记（或更新）某保司某自然月已结算。同一 (insurer_id, month) 只保留一条
    记录——重复标记时更新 settled_at/settled_by/note，不会插出第二条（唯一约束
    uq_insurer_monthly_settlement 在数据库层面也兜底这一点）。这只是记账标记，
    不会改任何保费计算结果、不会触发任何自动化流程。"""
    existing = _settlement_record(session, insurer_id, month)
    if existing:
        existing.settled_at = datetime.now(timezone.utc)
        existing.settled_by = admin.id
        existing.note = note
        session.commit()
        return serialize(existing)
    item = InsurerMonthlySettlement(insurer_id=insurer_id, month=month, settled_by=admin.id, note=note)
    session.add(item)
    try:
        session.commit()
    except IntegrityError:
        # 并发标记同一 (insurer_id, month)：唯一约束兜底，退化为更新已存在的那条，
        # 保持接口幂等，而不是把 500 抛给调用方。
        session.rollback()
        existing = _settlement_record(session, insurer_id, month)
        if not existing:
            raise
        existing.settled_at = datetime.now(timezone.utc)
        existing.settled_by = admin.id
        existing.note = note
        session.commit()
        return serialize(existing)
    session.refresh(item)
    return serialize(item)


def unmark_insurer_month_settled(session: Session, insurer_id: int, month: str) -> bool:
    existing = _settlement_record(session, insurer_id, month)
    if not existing:
        return False
    session.delete(existing)
    session.commit()
    return True
