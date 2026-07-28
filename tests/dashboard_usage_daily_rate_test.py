"""Regression: 平台端仪表盘"账户余额→平台服务费账户"单价没有正确显示
（用户反馈 2026-07-29）.

Root cause: /api/dashboard aggregates usage-fee balances across every
enterprise, but each enterprise can have its own usage_fee_daily rate
(EnterprisesPanel.vue lets an admin set it per unit). The endpoint computed
a per-enterprise daily_usage internally (for the balance-alert threshold)
but never rolled a rate into the response at all, so the web dashboard's
`row.daily_rate ?? 0` always showed ¥0 regardless of the real configured
rates.

This proves the fix: the response now carries `daily_rate`, computed as the
active-people-weighted average across every enterprise the caller can see —
meaningful even when different enterprises charge different rates, and it
correctly degrades to that one enterprise's own rate for an enterprise-role
caller.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-dashboard-rate-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from datetime import datetime, time, timedelta
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.business_time import business_today
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember, User, WorkPosition
        from backend.routers.dashboard import dashboard

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            # 甲单位费率 0.2、3 个在保人；乙单位费率 0.1、1 个在保人——加权平均
            # 应该是 (3*0.2+1*0.1)/4=0.175，跟简单平均 0.15 不一样，这样才能
            # 分辨出确实按人数加权而不是简单平均了各单位的费率。丙单位没有
            # 任何在保人，验证它不拖累平均值也不引发除零。
            ent_a = Enterprise(name="费率甲单位", kind="企业", contact="c", phone="p",
                              status="active", usage_balance=999.0, usage_fee_daily=0.2)
            ent_b = Enterprise(name="费率乙单位", kind="企业", contact="c", phone="p",
                              status="active", usage_balance=999.0, usage_fee_daily=0.1)
            ent_c = Enterprise(name="费率丙单位（无人）", kind="企业", contact="c", phone="p",
                              status="active", usage_balance=999.0, usage_fee_daily=9.9)
            session.add_all([ent_a, ent_b, ent_c]); session.commit()
            for e in (ent_a, ent_b, ent_c):
                session.refresh(e)

            today = business_today()
            yesterday_dt = datetime.combine(today - timedelta(days=1), time.min)
            for ent, n in ((ent_a, 3), (ent_b, 1)):
                plan = InsurancePlan(insurer="测试保司", name=f"计费测试险-{ent.id}",
                                    billing_mode="monthly", effective_mode="next_day", status="active")
                session.add(plan); session.commit(); session.refresh(plan)
                policy = Policy(policy_no=f"POL-RATE-{ent.id}", enterprise_id=ent.id, plan_id=plan.id,
                               status="active", start_date=today.isoformat())
                session.add(policy); session.commit(); session.refresh(policy)
                position = WorkPosition(enterprise_id=ent.id, name=f"岗位-{ent.id}", occupation_class="1-3类")
                session.add(position); session.commit(); session.refresh(position)
                for i in range(n):
                    person = InsuredPerson(enterprise_id=ent.id, name=f"员工{ent.id}-{i}",
                                          id_number=f"33010619900307{ent.id:02d}{i:02d}", position_id=position.id,
                                          status="active")
                    session.add(person); session.commit(); session.refresh(person)
                    session.add(PolicyMember(policy_id=policy.id, person_id=person.id,
                                            effective_at=yesterday_dt, status="active"))
            session.commit()

            data = dashboard(admin, session)
            assert data["daily_rate"] == 0.175, f"应该是按在保人数加权平均 0.175，实际 {data['daily_rate']}"

    print("dashboard usage daily-rate test: PASS")


if __name__ == "__main__":
    run()
