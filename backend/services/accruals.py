import calendar
from datetime import date, datetime, time, timedelta

from ..core.business_time import as_business_time, business_now, business_today
from ..models import PolicyMember


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
