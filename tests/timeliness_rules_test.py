"""Versioned product timing rules (v4.2 §8, §10).

This module is the only date algorithm in the system: 前端、报表和 Java 镜像
不得各自复制日期算法 (§8). If a second implementation ever appears, these
assertions are what it must match — and the reason it should not exist.

The snapshot exists so a later product edit cannot silently rewrite history:
the rule in force at the moment of an operation is frozen alongside it.
"""
import os
import sys
from datetime import datetime as D
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "rules-test-key")

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.models import InsurancePlan
from backend.services.timeliness_rules import (
    RULE_VERSION,
    feedback_deadline,
    normalize_enrollment,
    normalize_termination,
    rule_snapshot,
)


def plan(**kwargs) -> InsurancePlan:
    values = dict(name="测试产品", insurer="测试保司", price=30,
                  billing_mode="monthly", effective_mode="next_day")
    values.update(kwargs)
    return InsurancePlan(**values)


def test_snapshot_freezes_grace_by_billing_mode():
    """§20.3 月保单入离职反馈允许 24 小时宽限；按天产品不设宽限。"""
    assert rule_snapshot(plan(billing_mode="monthly"))["feedback_grace_seconds"] == 86400
    assert rule_snapshot(plan(billing_mode="daily"))["feedback_grace_seconds"] == 0


def test_snapshot_carries_the_rule_version():
    snap = rule_snapshot(plan())
    assert snap["rule_version"] == RULE_VERSION
    assert isinstance(RULE_VERSION, int)


def test_snapshot_records_business_timezone():
    # §8 时区是版本化规则的一部分，不能依赖进程级全局值来解释历史。
    assert rule_snapshot(plan())["business_timezone"]


def test_normalize_termination_monthly_is_next_business_midnight():
    r = rule_snapshot(plan(billing_mode="monthly"))
    assert normalize_termination(D(2026, 3, 31, 17, 0), r) == D(2026, 4, 1, 0, 0)


def test_normalize_termination_daily_is_exact():
    r = rule_snapshot(plan(billing_mode="daily"))
    assert normalize_termination(D(2026, 3, 31, 17, 0), r) == D(2026, 3, 31, 17, 0)


def test_normalize_enrollment_next_day_mode():
    r = rule_snapshot(plan(effective_mode="next_day"))
    assert normalize_enrollment(D(2026, 3, 1, 9, 0), r) == D(2026, 3, 2, 0, 0)


def test_normalize_enrollment_immediate_mode():
    r = rule_snapshot(plan(effective_mode="immediate"))
    assert normalize_enrollment(D(2026, 3, 1, 9, 0), r) == D(2026, 3, 1, 9, 0)


def test_feedback_deadline_applies_grace_only_for_monthly():
    assert feedback_deadline("enrollment", D(2026, 3, 1, 9, 0),
                             rule_snapshot(plan(billing_mode="monthly"))) == D(2026, 3, 2, 9, 0)
    assert feedback_deadline("enrollment", D(2026, 3, 1, 9, 0),
                             rule_snapshot(plan(billing_mode="daily"))) == D(2026, 3, 1, 9, 0)


def test_feedback_deadline_grace_applies_to_termination_too():
    # 已确认口径：月保单入职、离职反馈均允许 24 小时宽限。
    assert feedback_deadline("termination", D(2026, 3, 1, 9, 0),
                             rule_snapshot(plan(billing_mode="monthly"))) == D(2026, 3, 2, 9, 0)


def test_min_coverage_floor_is_respected():
    """§10 最短保障周期由 normalize_termination 统一处理。"""
    r = {**rule_snapshot(plan(billing_mode="daily")), "min_coverage_seconds": 7 * 86400}
    assert normalize_termination(D(2026, 3, 3), r, coverage_started_at=D(2026, 3, 1)) == D(2026, 3, 8)


def test_min_coverage_does_not_pull_termination_earlier():
    # 已满足最短周期时，不得把停保时间往前拉。
    r = {**rule_snapshot(plan(billing_mode="daily")), "min_coverage_seconds": 2 * 86400}
    assert normalize_termination(D(2026, 3, 20), r, coverage_started_at=D(2026, 3, 1)) == D(2026, 3, 20)


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items())
             if n.startswith("test_") and callable(f)]
    for name, fn in tests:
        fn()
        print(f"  {name} ok")
    print(f"timeliness rules tests passed ({len(tests)})")
