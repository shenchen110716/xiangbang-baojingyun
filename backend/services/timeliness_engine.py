"""Pure timeliness judgement (v4.2 §9, §10, §11).

No database and no clock: `now` is always injected. That is what makes the
ordered ladders deterministic and exhaustively testable — every rule the
business will argue about is decided here, from plain values, with nothing
hidden in a query.

The ladders are *ordered*. Evaluating them out of order silently changes
verdicts (e.g. a conflict resolved as "timely" by picking the first candidate),
so each function walks its rungs top-down and returns at the first match.
"""
from dataclasses import dataclass
from datetime import datetime
from typing import Optional

from .timeliness_rules import feedback_deadline, normalize_termination

# §9 参保及时率 = (timely + early) / (timely + early + late + missing)。
# 提前参保计入及时但单列成本（§20.4）。
ENROLLMENT_NUMERATOR = frozenset({"timely", "early"})
ENROLLMENT_DENOMINATOR = frozenset({"timely", "early", "late", "missing"})

# §10 停保及时率 = timely / (timely + premature + late + missing)。
# 提前停保不计及时并单列保障缺口（§20.4）。
TERMINATION_NUMERATOR = frozenset({"timely"})
TERMINATION_DENOMINATOR = frozenset({"timely", "premature", "late", "missing"})

# pending/unmatched/conflict 不进任何分子分母：还没发生、还没匹配或有争议的事件
# 既不算功劳也不算过失（§20.6）。


@dataclass(frozen=True)
class Coverage:
    effective_at: datetime
    terminated_at: Optional[datetime] = None

    def live_at(self, moment: datetime) -> bool:
        """§9「曾提前生效但在 H 前已终止的保障，不构成 H 时刻的连续有效保障」。"""
        if self.effective_at > moment:
            return False
        return self.terminated_at is None or self.terminated_at > moment


@dataclass(frozen=True)
class EnrollmentInput:
    hire_at: datetime
    now: datetime
    coverages: list
    rule: dict


@dataclass(frozen=True)
class TerminationInput:
    leave_at: Optional[datetime]
    now: datetime
    terminated_at: Optional[datetime]
    rule: dict


@dataclass(frozen=True)
class Verdict:
    status: str
    expected_at: Optional[datetime] = None
    actual_at: Optional[datetime] = None
    delay_seconds: int = 0
    early_seconds: int = 0
    coverage_gap_seconds: int = 0


def _seconds(later: datetime, earlier: datetime) -> int:
    return int((later - earlier).total_seconds())


def judge_enrollment(data: EnrollmentInput) -> Verdict:
    """§9 参保阶梯，严格按序。"""
    hire = data.hire_at

    # 入职尚未发生：不是"漏保"，只是还没到。
    if hire > data.now:
        return Verdict("pending", expected_at=hire)

    live = [c for c in data.coverages if c.live_at(hire)]
    if len(live) > 1:
        # 多个候选保障期时判 conflict 而非挑一个：猜错会让该人的指标长期失真。
        return Verdict("conflict", expected_at=hire)

    if live:
        coverage = live[0]
        if coverage.effective_at == hire:
            return Verdict("timely", expected_at=hire, actual_at=coverage.effective_at)
        # E < H：提前生效且入职时仍有效。
        return Verdict("early", expected_at=hire, actual_at=coverage.effective_at,
                       early_seconds=_seconds(hire, coverage.effective_at))

    # 入职时无有效保障：看之后是否补上了。
    later = sorted((c for c in data.coverages if c.effective_at > hire),
                   key=lambda c: c.effective_at)
    if later:
        first = later[0]
        gap = _seconds(first.effective_at, hire)
        return Verdict("late", expected_at=hire, actual_at=first.effective_at,
                       delay_seconds=gap, coverage_gap_seconds=gap)

    return Verdict("missing", expected_at=hire)


def judge_termination(data: TerminationInput) -> Verdict:
    """§10 停保阶梯，严格按序。"""
    if data.leave_at is None:
        # 没有离职事实：此人仍在职，不构成停保事件。
        return Verdict("pending")

    expected = normalize_termination(data.leave_at, data.rule)
    actual = data.terminated_at

    if actual is None:
        # 应停时点还没到：尚未构成"漏停"。
        if data.now < expected:
            return Verdict("pending", expected_at=expected)
        return Verdict("missing", expected_at=expected)

    if actual == expected:
        return Verdict("timely", expected_at=expected, actual_at=actual)

    if actual < expected:
        # 提前停保：保障提前中断，永不计入及时，缺口单列（§20.4）。
        return Verdict("premature", expected_at=expected, actual_at=actual,
                       coverage_gap_seconds=_seconds(expected, actual))

    return Verdict("late", expected_at=expected, actual_at=actual,
                   delay_seconds=_seconds(actual, expected))


def judge_feedback(*, event_at: datetime, reported_at: Optional[datetime],
                   rule: dict, event_type: str = "enrollment") -> Verdict:
    """§11.2 反馈及时率。宽限只在这里生效，绝不影响保障阶梯（§20.3）。"""
    deadline = feedback_deadline(event_type, event_at, rule)
    if reported_at is None:
        return Verdict("missing", expected_at=deadline)
    if reported_at <= deadline:
        return Verdict("timely", expected_at=deadline, actual_at=reported_at)
    return Verdict("late", expected_at=deadline, actual_at=reported_at,
                   delay_seconds=_seconds(reported_at, deadline))


def _rate(numerator: int, denominator: int) -> Optional[float]:
    """None, not 0 or 100, when nothing is due: an empty project is neither
    perfect nor failing, and a fake number would be read as either."""
    if denominator == 0:
        return None
    return round(numerator / denominator * 100, 2)


def summarise(*, enrollment: list, termination: list) -> dict:
    """§13 卡片口径；也是 Phase 4 消费的 /api/timeliness/summary 响应形状。

    入职和离职分别按业务事件计数，不以员工人数代替事件数（§20.5）。
    """
    e_due = sum(1 for s in enrollment if s in ENROLLMENT_DENOMINATOR)
    e_timely = sum(1 for s in enrollment if s in ENROLLMENT_NUMERATOR)
    t_due = sum(1 for s in termination if s in TERMINATION_DENOMINATOR)
    t_timely = sum(1 for s in termination if s in TERMINATION_NUMERATOR)

    return {
        "enrollment_due": e_due,
        "enrollment_timely": e_timely,
        "enrollment_late": sum(1 for s in enrollment if s == "late"),
        "enrollment_missing": sum(1 for s in enrollment if s == "missing"),
        "termination_due": t_due,
        "termination_timely": t_timely,
        "termination_premature": sum(1 for s in termination if s == "premature"),
        "termination_late": sum(1 for s in termination if s == "late"),
        "termination_missing": sum(1 for s in termination if s == "missing"),
        "enrollment_rate": _rate(e_timely, e_due),
        "termination_rate": _rate(t_timely, t_due),
        "composite_rate": _rate(e_timely + t_timely, e_due + t_due),
    }
