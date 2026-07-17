"""Versioned product timing rules — the only date algorithm in the system (§8).

前端、报表和 Java 镜像不得各自复制日期算法 (§8). Every "when should coverage
have started/ended?" decision lives here. A second implementation anywhere else
is a bug, not an optimisation.

Rules are *snapshotted* at the moment an operation happens (`rule_snapshot`) and
stored alongside it, so editing a product later cannot silently rewrite what
past operations should have done.

Timezone note: `backend/core/business_time.py` reads one process-wide
BUSINESS_TIMEZONE env var, but §8 makes the timezone part of the versioned rule
— a historical verdict must stay interpretable even if the deployment's global
timezone changes. So the snapshot records its own `business_timezone` and every
function here uses that, via ZoneInfo. The global module's behaviour is left
alone; other subsystems depend on it.
"""
import os
from datetime import datetime, timedelta
from typing import Optional
from zoneinfo import ZoneInfo

from ..models import InsurancePlan

# Bump whenever any algorithm below changes. It participates in the recalc
# idempotency key, so a bump makes previously computed results recomputable
# instead of silently stale.
RULE_VERSION: int = 1

# 月保单入职、离职反馈均允许 24 小时宽限；按天或即时产品不设宽限（§20.3）。
_MONTHLY_FEEDBACK_GRACE_SECONDS = 86400
_DEFAULT_TIMEZONE = "Australia/Melbourne"


def rule_snapshot(plan: InsurancePlan) -> dict:
    """Freeze the timing semantics in force for this plan, right now."""
    billing_mode = plan.billing_mode or "monthly"
    return {
        "billing_mode": billing_mode,
        "effective_mode": plan.effective_mode or "next_day",
        # 月保单的离职日期表示最后工作日；按天产品的离职时间即确切时刻（§10）。
        "leave_is_last_working_day": billing_mode == "monthly",
        "min_coverage_seconds": 0,
        "business_timezone": os.getenv("BUSINESS_TIMEZONE", _DEFAULT_TIMEZONE),
        "feedback_grace_seconds": (
            _MONTHLY_FEEDBACK_GRACE_SECONDS if billing_mode == "monthly" else 0
        ),
        "rule_version": RULE_VERSION,
    }


def _zone(rule: dict) -> ZoneInfo:
    return ZoneInfo(rule.get("business_timezone") or _DEFAULT_TIMEZONE)


def _next_business_midnight(value: datetime, rule: dict) -> datetime:
    """00:00 of the following business day, in the rule's own timezone.

    Naive input stays naive: the engine works in a single frame per computation
    and mixing aware/naive here would raise mid-ladder.
    """
    if value.tzinfo is None:
        local = value
        return (local + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    local = value.astimezone(_zone(rule))
    bumped = (local + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return bumped.astimezone(value.tzinfo)


def normalize_enrollment(actual_hire_at: datetime, rule: dict) -> datetime:
    """When coverage should have started for this hire."""
    if rule.get("effective_mode") == "immediate":
        return actual_hire_at
    return _next_business_midnight(actual_hire_at, rule)


def normalize_termination(actual_leave_at: datetime, rule: dict, *,
                          coverage_started_at: Optional[datetime] = None) -> datetime:
    """When coverage should have ended for this departure (§10).

    For a monthly product the leave date means "last working day", so coverage
    should run to the end of it — i.e. next business midnight. A daily product
    ends at the exact moment.

    A minimum coverage period can only ever push the end later, never earlier:
    pulling it earlier would manufacture a coverage gap the employer never had.
    """
    if rule.get("leave_is_last_working_day"):
        expected = _next_business_midnight(actual_leave_at, rule)
    else:
        expected = actual_leave_at

    floor_seconds = int(rule.get("min_coverage_seconds") or 0)
    if floor_seconds and coverage_started_at is not None:
        earliest_end = coverage_started_at + timedelta(seconds=floor_seconds)
        if earliest_end > expected:
            expected = earliest_end
    return expected


def feedback_deadline(event_type: str, actual_business_at: datetime, rule: dict) -> datetime:
    """By when the employer should have reported this event (§11.2).

    The grace window lives here and nowhere else — it explains feedback
    responsibility only, and must never touch the coverage ladders (§20.3).
    """
    if event_type not in ("enrollment", "termination"):
        raise ValueError(f"未知的用工事件类型：{event_type}")
    return actual_business_at + timedelta(seconds=int(rule.get("feedback_grace_seconds") or 0))
