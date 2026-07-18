import calendar
from datetime import date, datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.business_time import as_business_time, business_now, business_today
from ..models import Policy, PolicyMember
from .serialization import amount


def period_amount(unit_price: float, billing_mode: str, start: date, end: date) -> float:
    """Prorate a per-day or per-natural-month unit price over billable dates."""
    if start > end:
        return 0
    if billing_mode == "daily":
        return float(unit_price or 0) * ((end - start).days + 1)
    total = 0.0
    cursor = start
    while cursor <= end:
        month_days = calendar.monthrange(cursor.year, cursor.month)[1]
        month_end = date(cursor.year, cursor.month, month_days)
        segment_end = min(end, month_end)
        active_days = (segment_end - cursor).days + 1
        total += float(unit_price or 0) * active_days / month_days
        cursor = segment_end + timedelta(days=1)
    return total


def last_billable_date(terminated_at: datetime | None) -> date | None:
    """A termination at 00:00 ends coverage before that calendar day starts."""
    if terminated_at is None:
        return None
    terminated_date = terminated_at.date()
    return terminated_date - timedelta(days=1) if terminated_at.time() == time.min else terminated_date


def billable_date_range(
    member: PolicyMember,
    requested_start: date,
    requested_end: date,
    as_of: date | datetime | None = None,
) -> tuple[date, date] | None:
    """Intersect requested dates with coverage and never accrue beyond today."""
    current_time = business_now() if as_of is None else (as_business_time(as_of) if isinstance(as_of, datetime) else None)
    cutoff_date = current_time.date() if current_time is not None else (as_of or business_today())
    if current_time is not None and as_business_time(member.effective_at) > current_time:
        return None
    cutoff = min(requested_end, cutoff_date)
    period_start = max(requested_start, member.effective_at.date())
    coverage_end = last_billable_date(member.terminated_at)
    period_end = min(cutoff, coverage_end) if coverage_end is not None else cutoff
    return None if period_start > period_end else (period_start, period_end)


def usage_person_days(
    session: Session,
    enterprise_id: int,
    requested_start: date | None = None,
    requested_end: date | None = None,
) -> dict:
    """Count unique valid coverage days per person, merging overlapping periods."""
    today = business_today()
    end = min(requested_end or today, today)
    intervals: dict[int, list[tuple[date, date]]] = {}
    members = session.scalars(
        select(PolicyMember)
        .join(Policy, Policy.id == PolicyMember.policy_id)
        .where(Policy.enterprise_id == enterprise_id)
        .order_by(PolicyMember.person_id.asc(), PolicyMember.effective_at.asc())
    )
    for member in members:
        start = requested_start or member.effective_at.date()
        billable = billable_date_range(member, start, end)
        if billable is not None:
            intervals.setdefault(member.person_id, []).append(billable)

    total_days = 0
    active_people = 0
    for person_intervals in intervals.values():
        merged: list[list[date]] = []
        for start, finish in person_intervals:
            if not merged or start > merged[-1][1] + timedelta(days=1):
                merged.append([start, finish])
            elif finish > merged[-1][1]:
                merged[-1][1] = finish
        total_days += sum((finish - start).days + 1 for start, finish in merged)
        if any(start <= today <= finish for start, finish in merged):
            active_people += 1
    return {
        "person_days": total_days,
        "active_people": active_people,
        "start_date": requested_start.isoformat() if requested_start else None,
        "end_date": end.isoformat(),
    }


def usage_account_view(session: Session, enterprise) -> dict:
    """服务费（平台使用费）账户口径，三端展示与参停保门禁统一以此为准：

    - 充值总额 recharged = enterprise.usage_balance —— 该字段只在充值/入账时累加、
      从不扣减，因此等于历次充值总额；
    - 总使用费 consumed = 全时段计费人天 × 当前日费率；
    - 可用余额 available = 充值总额 − 总使用费。

    日费率按当前值折算历史人天（系统不保存费率变更历史，属可接受的近似）。"""
    rate = float(enterprise.usage_fee_daily or 0.1)
    lifetime = usage_person_days(session, enterprise.id, requested_end=business_today())
    recharged = amount(enterprise.usage_balance)
    consumed = amount(lifetime["person_days"] * rate)
    return {
        "recharged": recharged,
        "consumed": consumed,
        "available": amount(recharged - consumed),
        "active_people": lifetime["active_people"],
        "daily_rate": rate,
    }
