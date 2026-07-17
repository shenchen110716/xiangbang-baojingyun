"""Pure timeliness engine ladders (v4.2 §9, §10, §11).

The engine takes plain values and returns verdicts with no database and no
clock, which is what makes the ordered ladders exhaustively testable. Every
rule the business argues about lives here, so these assertions are the contract:

- 提前参保计入及时但单列成本；提前停保不计及时并单列保障缺口 (§20.4)
- 入职和离职分别按业务事件计数，不以人数代替事件数 (§20.5)
- 宽限期只影响反馈及时率，不改变保障主及时率 (§20.3)
- 候选保障期有歧义时判 conflict，绝不猜 (§9)
"""
import sys
from datetime import datetime as D
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.services.timeliness_engine import (
    Coverage,
    EnrollmentInput,
    TerminationInput,
    judge_enrollment,
    judge_feedback,
    judge_termination,
    summarise,
)

RULE = dict(billing_mode="monthly", effective_mode="next_day",
            leave_is_last_working_day=True, min_coverage_seconds=0,
            business_timezone="Australia/Melbourne", feedback_grace_seconds=86400)
DAILY = {**RULE, "billing_mode": "daily", "effective_mode": "immediate",
         "leave_is_last_working_day": False, "feedback_grace_seconds": 0}


# --- §9 参保阶梯 ---------------------------------------------------------

def test_future_hire_is_pending_and_out_of_denominator():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 5, 1), now=D(2026, 4, 1),
                                         coverages=[], rule=RULE))
    assert r.status == "pending"


def test_coverage_starting_exactly_at_hire_is_timely():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 1), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 1), None)], rule=RULE))
    assert r.status == "timely" and r.delay_seconds == 0 and r.early_seconds == 0


def test_coverage_starting_before_hire_and_live_at_hire_is_early():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 10), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 1), None)], rule=RULE))
    assert r.status == "early" and r.early_seconds == 9 * 86400


def test_early_coverage_already_terminated_before_hire_does_not_count_as_live():
    """§9 曾提前生效但在 H 前已终止的保障，不构成 H 时刻的连续有效保障。"""
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 10), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 1), D(2026, 3, 5))],
                                         rule=RULE))
    assert r.status == "missing"


def test_first_coverage_after_hire_is_late_with_gap():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 1), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 4), None)], rule=RULE))
    assert r.status == "late"
    assert r.delay_seconds == 3 * 86400
    assert r.coverage_gap_seconds == 3 * 86400


def test_hire_reached_with_no_coverage_at_all_is_missing():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 1), now=D(2026, 4, 1),
                                         coverages=[], rule=RULE))
    assert r.status == "missing"


def test_ambiguous_coverage_candidates_are_conflict_not_guessed():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 1), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 1), None),
                                                    Coverage(D(2026, 3, 1), None)],
                                         rule=RULE))
    assert r.status == "conflict"


# --- §10 停保阶梯 --------------------------------------------------------

def test_monthly_last_working_day_expects_next_day_midnight():
    """§10 月保单且离职日期表示最后工作日：S 为离职业务日次日 00:00。"""
    r = judge_termination(TerminationInput(leave_at=D(2026, 3, 31, 17, 0), now=D(2026, 4, 10),
                                           terminated_at=D(2026, 4, 1, 0, 0), rule=RULE))
    assert r.expected_at == D(2026, 4, 1, 0, 0) and r.status == "timely"


def test_daily_product_expects_exact_leave_time_with_no_grace():
    r = judge_termination(TerminationInput(leave_at=D(2026, 3, 31, 17, 0), now=D(2026, 4, 10),
                                           terminated_at=D(2026, 3, 31, 17, 0), rule=DAILY))
    assert r.expected_at == D(2026, 3, 31, 17, 0) and r.status == "timely"


def test_termination_before_expected_is_premature_and_never_timely():
    r = judge_termination(TerminationInput(leave_at=D(2026, 3, 31, 17, 0), now=D(2026, 4, 10),
                                           terminated_at=D(2026, 3, 20), rule=RULE))
    assert r.status == "premature" and r.coverage_gap_seconds > 0


def test_termination_after_expected_is_late_with_excess_period():
    r = judge_termination(TerminationInput(leave_at=D(2026, 3, 31, 17, 0), now=D(2026, 4, 10),
                                           terminated_at=D(2026, 4, 6), rule=RULE))
    assert r.status == "late" and r.delay_seconds == 5 * 86400


def test_no_leave_fact_is_pending():
    r = judge_termination(TerminationInput(leave_at=None, now=D(2026, 4, 10),
                                           terminated_at=None, rule=RULE))
    assert r.status == "pending"


def test_past_expected_with_no_termination_is_missing():
    r = judge_termination(TerminationInput(leave_at=D(2026, 3, 31, 17, 0), now=D(2026, 4, 10),
                                           terminated_at=None, rule=RULE))
    assert r.status == "missing"


# --- §11 比率与宽限 ------------------------------------------------------

def test_enrollment_rate_counts_early_as_timely():
    """§9 参保及时率 = (timely + early) / (timely + early + late + missing)。"""
    s = summarise(enrollment=["timely", "early", "late", "missing", "pending", "conflict"],
                  termination=[])
    assert s["enrollment_rate"] == 50.0
    assert s["enrollment_due"] == 4          # pending/conflict 不进分母
    assert s["enrollment_timely"] == 2       # timely + early


def test_termination_rate_excludes_premature_from_numerator():
    """§10 停保及时率 = timely / (timely + premature + late + missing)。"""
    s = summarise(enrollment=[], termination=["timely", "premature", "late", "missing"])
    assert s["termination_rate"] == 25.0


def test_composite_counts_events_not_people():
    """§11.1 入职和离职各算一个业务事件。"""
    s = summarise(enrollment=["timely", "early"], termination=["timely", "late"])
    assert s["composite_rate"] == 75.0


def test_monthly_feedback_within_24h_grace_is_timely():
    assert judge_feedback(event_at=D(2026, 3, 1, 9, 0), reported_at=D(2026, 3, 2, 8, 0),
                          rule=RULE).status == "timely"


def test_monthly_feedback_beyond_24h_grace_is_late():
    assert judge_feedback(event_at=D(2026, 3, 1, 9, 0), reported_at=D(2026, 3, 2, 10, 0),
                          rule=RULE).status == "late"


def test_daily_product_has_zero_grace():
    assert judge_feedback(event_at=D(2026, 3, 1, 9, 0), reported_at=D(2026, 3, 1, 9, 1),
                          rule=DAILY).status == "late"


def test_grace_never_changes_the_coverage_verdict():
    """§20.3 宽限只解释反馈责任，不修改保障主及时率。"""
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026, 3, 1), now=D(2026, 4, 1),
                                         coverages=[Coverage(D(2026, 3, 4), None)], rule=RULE))
    assert r.status == "late"


def test_no_events_yields_no_rate_not_a_fake_hundred():
    """空分母必须返回 None，而不是 0 或 100——那会让空项目看起来完美或糟糕。"""
    s = summarise(enrollment=["pending"], termination=[])
    assert s["enrollment_due"] == 0
    assert s["enrollment_rate"] is None
    assert s["composite_rate"] is None


if __name__ == "__main__":
    tests = [(n, f) for n, f in sorted(globals().items())
             if n.startswith("test_") and callable(f)]
    for name, fn in tests:
        fn()
        print(f"  {name} ok")
    print(f"timeliness engine tests passed ({len(tests)})")
