"""Regression: 参停保及时率按 billing_mode 分别处理（用户反馈 2026-07-29）.

Two rules, end to end through recalculate() (not just the pure ladder):

- 月保 (billing_mode='monthly'): only compare at day granularity. A real
  hire/leave time with a clock component (e.g. "08:00 打卡") must not be
  judged "early"/"late" against a coverage boundary that always lands on
  midnight — that mismatch was making enrollment/termination rates look
  stuck near 0% in production even when HR acted the same day.
- 日保 (billing_mode='daily', effective_mode='immediate'): not counted at
  all for now. recalculate() must not write a current result row for it,
  and a batch containing only daily-billing facts must still reach
  "completed" instead of hanging at imported_pending_calculation forever.
"""
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def _utc(y, m, d, h=0, mi=0):
    return datetime(y, m, d, h, mi, tzinfo=timezone.utc)


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-timeliness-billing-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import (
            ActualEmployer,
            Enterprise,
            EmploymentFact,
            EmploymentFeedbackBatch,
            EmploymentTimelinessResult,
            InsurancePlan,
            InsuredPerson,
            PolicyMember,
            Policy,
            WorkPosition,
        )
        from backend.services.timeliness_recalc import recalculate

        startup()
        with SessionLocal() as session:
            enterprise = Enterprise(name="计费模式测试企业", kind="企业", contact="c", phone="p",
                                    status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            employer = ActualEmployer(enterprise_id=enterprise.id, name="项目 A")
            session.add(employer); session.commit(); session.refresh(employer)

            monthly_plan = InsurancePlan(insurer="测试保司", name="月保测试险", billing_mode="monthly",
                                        effective_mode="next_day", status="active")
            daily_plan = InsurancePlan(insurer="测试保司", name="日保测试险", billing_mode="daily",
                                      effective_mode="immediate", status="active")
            session.add_all([monthly_plan, daily_plan]); session.commit()
            session.refresh(monthly_plan); session.refresh(daily_plan)

            monthly_position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                           name="月保岗位", occupation_class="1-3类", status="approved",
                                           plan_id=monthly_plan.id)
            daily_position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                         name="日保岗位", occupation_class="1-3类", status="approved",
                                         plan_id=daily_plan.id)
            session.add_all([monthly_position, daily_position]); session.commit()
            session.refresh(monthly_position); session.refresh(daily_position)

            monthly_policy = Policy(policy_no="POL-MONTHLY-1", enterprise_id=enterprise.id,
                                   plan_id=monthly_plan.id, status="active", start_date="2026-01-01")
            daily_policy = Policy(policy_no="POL-DAILY-1", enterprise_id=enterprise.id,
                                 plan_id=daily_plan.id, status="active", start_date="2026-01-01")
            session.add_all([monthly_policy, daily_policy]); session.commit()
            session.refresh(monthly_policy); session.refresh(daily_policy)

            batch = EmploymentFeedbackBatch(enterprise_id=enterprise.id, source_type="manual_import",
                                           source_filename="t.xlsx", source_file_hash="h1",
                                           status="imported_pending_calculation")
            session.add(batch); session.commit(); session.refresh(batch)

            # 月保：真实入职带钟点（08:00 打卡），保障生效卡在当天零点——同一
            # 自然日，应该判"及时"，不该因为钟点对不上判"早"。
            monthly_person = InsuredPerson(enterprise_id=enterprise.id, name="月保张三",
                                          id_number="330106199003077005", position_id=monthly_position.id)
            session.add(monthly_person); session.commit(); session.refresh(monthly_person)
            session.add(PolicyMember(policy_id=monthly_policy.id, person_id=monthly_person.id,
                                    effective_at=_utc(2026, 3, 1, 0, 0), status="active"))
            session.commit()
            monthly_fact = EmploymentFact(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                         person_id=monthly_person.id, person_name="月保张三",
                                         actual_hire_at=_utc(2026, 3, 1, 8, 0), batch_id=batch.id,
                                         status="active")
            session.add(monthly_fact); session.commit(); session.refresh(monthly_fact)

            # 日保（即时生效）：先不统计，不该产生 current 结果行。
            daily_person = InsuredPerson(enterprise_id=enterprise.id, name="日保李四",
                                        id_number="44030119900307700X", position_id=daily_position.id)
            session.add(daily_person); session.commit(); session.refresh(daily_person)
            session.add(PolicyMember(policy_id=daily_policy.id, person_id=daily_person.id,
                                    effective_at=_utc(2026, 3, 5, 9, 0), status="active"))
            session.commit()
            daily_fact = EmploymentFact(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                       person_id=daily_person.id, person_name="日保李四",
                                       actual_hire_at=_utc(2026, 3, 5, 9, 0), batch_id=batch.id,
                                       status="active")
            session.add(daily_fact); session.commit(); session.refresh(daily_fact)

            recalculate(session, fact_id=monthly_fact.id, now=_utc(2026, 4, 1))
            recalculate(session, fact_id=daily_fact.id, now=_utc(2026, 4, 1))
            session.commit()

            monthly_result = session.scalar(select(EmploymentTimelinessResult).where(
                EmploymentTimelinessResult.employment_fact_id == monthly_fact.id,
                EmploymentTimelinessResult.operation_type == "enrollment",
                EmploymentTimelinessResult.status == "current"))
            assert monthly_result is not None, "月保应该正常写出结果"
            assert monthly_result.timeliness_status == "timely", \
                f"月保同一天参保应判及时，实为 {monthly_result.timeliness_status}"

            daily_results = list(session.scalars(select(EmploymentTimelinessResult).where(
                EmploymentTimelinessResult.employment_fact_id == daily_fact.id,
                EmploymentTimelinessResult.status == "current")))
            assert daily_results == [], f"日保（即时生效）不该产生 current 结果行，实际有 {len(daily_results)} 条"

            session.refresh(batch)
            assert batch.status == "completed", \
                f"批次里的日保事实虽然不写结果，也该算处理完了，实际批次状态 {batch.status}"

    print("timeliness billing-mode test: PASS")


if __name__ == "__main__":
    run()
