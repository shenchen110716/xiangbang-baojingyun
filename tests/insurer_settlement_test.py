"""财务管理: insurer sees own-insurer settlement rows only, no profit fields."""
import os
import sys
import tempfile
from pathlib import Path

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app, startup  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Enterprise, Insurer, InsurancePlan, InsuredPerson, Policy, PolicyMember, User  # noqa: E402

startup()
client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="结算保司A"); insurer_b = Insurer(name="结算保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="结算保司A", name="方案A", price=100, commission_rate=0.2, profit_amount=10, insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="结算保司B", name="方案B", price=200, insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="结算测试企业"); s.add(enterprise); s.flush()
        s.add(Policy(policy_no="POL-SETTLE-A", enterprise_id=enterprise.id, plan_id=plan_a.id, premium=100, status="active"))
        s.add(Policy(policy_no="POL-SETTLE-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=200, status="active"))
        s.flush()
        if not s.query(User).filter(User.username == "settle_insurer_a").first():
            s.add(User(username="settle_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id))
        s.commit()


def test_insurer_settlement_scoped_and_hides_profit():
    _setup()
    login = client.post("/api/auth/login", json={"username": "settle_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/insurer-portal/settlement", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert all(row["policy_no"] != "POL-SETTLE-B" for row in body["rows"])
    assert any(row["policy_no"] == "POL-SETTLE-A" for row in body["rows"])
    for row in body["rows"]:
        assert "profit_amount" not in row
        assert "agent_commission_amount" not in row


def test_settlement_total_cumulative_premium_reflects_real_headcount():
    """回归用例：之前直接加总 Policy.premium 这个静态列，而真实数据里这一列
    经常没被填过（新建保单不强制录入，实际保费按人动态算），导致"在保保费
    合计"页面上一直显示 0。修复后按累计口径重算，哪怕 Policy.premium 本身
    是 0 也要得出非零合计。"""
    with SessionLocal() as s:
        insurer = Insurer(name="累计保费回归测试保司"); s.add(insurer); s.flush()
        plan = InsurancePlan(insurer="累计保费回归测试保司", name="方案", price=100, commission_rate=0.1, insurer_id=insurer.id)
        s.add(plan); s.flush()
        enterprise = Enterprise(name="累计保费回归测试企业"); s.add(enterprise); s.flush()
        # Policy.premium 故意留 0，模拟真实数据里这一列没被填过的情况。
        policy = Policy(policy_no="POL-ZERO-PREMIUM", enterprise_id=enterprise.id, plan_id=plan.id, premium=0, status="active")
        s.add(policy); s.flush()
        person = InsuredPerson(enterprise_id=enterprise.id, name="非零保费甲", id_number="340123199001019993", status="active", policy_id=policy.id)
        s.add(person); s.flush()
        s.add(PolicyMember(policy_id=policy.id, person_id=person.id, status="active"))
        user = User(username="cumulative_premium_insurer", password_hash=pwd.hash("test1234"), name="保司账号", role="insurer", insurer_id=insurer.id)
        s.add(user); s.commit()

    login = client.post("/api/auth/login", json={"username": "cumulative_premium_insurer", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/insurer-portal/settlement", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["total_cumulative_premium"] > 0
    row = next(r for r in body["rows"] if r["policy_no"] == "POL-ZERO-PREMIUM")
    assert row["insured_count"] == 1
    assert row["premium"] > 0
    assert row["premium"] == body["total_cumulative_premium"]


def test_settlement_cumulative_includes_already_stopped_people():
    """累计口径不能只看"现在还在保的人"——已经停保的人在他们停保前的在保
    区间同样要计入累计总额，且累计合计不应该因为保单本身状态不是 active
    就被漏掉。"""
    from datetime import datetime, timedelta, timezone
    from backend.core.business_time import business_today

    with SessionLocal() as s:
        insurer = Insurer(name="停保累计测试保司"); s.add(insurer); s.flush()
        plan = InsurancePlan(insurer="停保累计测试保司", name="方案", price=100, commission_rate=0.1, billing_mode="daily", insurer_id=insurer.id)
        s.add(plan); s.flush()
        enterprise = Enterprise(name="停保累计测试企业"); s.add(enterprise); s.flush()
        policy = Policy(policy_no="POL-STOPPED-CUMULATIVE", enterprise_id=enterprise.id, plan_id=plan.id, premium=0, status="active")
        s.add(policy); s.flush()
        person = InsuredPerson(enterprise_id=enterprise.id, name="已停保甲", id_number="340123199001019994", status="stopped", policy_id=None)
        s.add(person); s.flush()
        today = business_today()
        effective = datetime(today.year, today.month, 1, tzinfo=timezone.utc)
        terminated = datetime.now(timezone.utc) - timedelta(days=1)
        s.add(PolicyMember(policy_id=policy.id, person_id=person.id, effective_at=effective, terminated_at=terminated, status="stopped"))
        user = User(username="stopped_cumulative_insurer", password_hash=pwd.hash("test1234"), name="保司账号", role="insurer", insurer_id=insurer.id)
        s.add(user); s.commit()

    login = client.post("/api/auth/login", json={"username": "stopped_cumulative_insurer", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/insurer-portal/settlement", headers=headers)
    assert resp.status_code == 200
    row = next(r for r in resp.json()["rows"] if r["policy_no"] == "POL-STOPPED-CUMULATIVE")
    assert row["insured_count"] == 1
    assert row["premium"] > 0


def test_monthly_premium_summary_detail_export_scoped():
    from datetime import datetime, timezone
    from backend.core.business_time import business_today

    with SessionLocal() as s:
        insurer_a = Insurer(name="月度保司A"); insurer_b = Insurer(name="月度保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="月度保司A", name="方案A", price=100, commission_rate=0.1, billing_mode="monthly", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="月度保司B", name="方案B", price=200, billing_mode="monthly", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="月度测试企业"); s.add(enterprise); s.flush()
        policy_a = Policy(policy_no="POL-MONTHLY-A", enterprise_id=enterprise.id, plan_id=plan_a.id, premium=100, status="active")
        policy_b = Policy(policy_no="POL-MONTHLY-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=200, status="active")
        s.add(policy_a); s.add(policy_b); s.flush()
        person_a = InsuredPerson(enterprise_id=enterprise.id, name="月度甲", id_number="340123199001019991", status="active", policy_id=policy_a.id)
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="月度乙", id_number="340123199001019992", status="active", policy_id=policy_b.id)
        s.add(person_a); s.add(person_b); s.flush()
        today = business_today()
        month_start = datetime(today.year, today.month, 1, tzinfo=timezone.utc)
        s.add(PolicyMember(policy_id=policy_a.id, person_id=person_a.id, effective_at=month_start, status="active"))
        s.add(PolicyMember(policy_id=policy_b.id, person_id=person_b.id, effective_at=month_start, status="active"))
        user_a = User(username="monthly_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        month = today.strftime("%Y-%m")

    login = client.post("/api/auth/login", json={"username": "monthly_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    summary = client.get("/api/insurer-portal/settlement/monthly", headers=headers)
    assert summary.status_code == 200
    current = next(row for row in summary.json() if row["month"] == month)
    assert current["total_premium"] > 0
    assert current["insured_count"] == 1

    detail = client.get(f"/api/insurer-portal/settlement/monthly/{month}", headers=headers)
    assert detail.status_code == 200
    rows = detail.json()
    assert any(row["person_name"] == "月度甲" for row in rows)
    assert all(row["person_name"] != "月度乙" for row in rows)
    detail_row = next(row for row in rows if row["person_name"] == "月度甲")
    assert detail_row["effective_at"] == month_start.date().isoformat()
    assert detail_row["terminated_at"] is None
    assert detail_row["billable_days"] > 0

    export = client.get(f"/api/insurer-portal/settlement/monthly/{month}/export", headers=headers)
    assert export.status_code == 200
    assert export.headers["content-type"].startswith("application/vnd.openxmlformats")
    assert len(export.content) > 0

    bad_month = client.get("/api/insurer-portal/settlement/monthly/not-a-month", headers=headers)
    assert bad_month.status_code == 400

    # 年份数字合法但超出 date() 支持范围的边界情况，不应该 500。
    for bad in ("0000-01", "99999-01", "2026-13", "2026-00"):
        resp = client.get(f"/api/insurer-portal/settlement/monthly/{bad}", headers=headers)
        assert resp.status_code == 400, f"{bad} should be rejected, got {resp.status_code}"

    # int() 会悄悄吃掉首尾空白/控制字符（如 "07\n"）——月份参数必须过严格正则，
    # 不能让这类输入蒙混过去再被拼进 Excel sheet 名 / Content-Disposition 头。
    import urllib.parse
    dirty_month = urllib.parse.quote(f"{month}\n\t")
    dirty_resp = client.get(f"/api/insurer-portal/settlement/monthly/{dirty_month}/export", headers=headers)
    assert dirty_resp.status_code == 400

    # 正常导出的响应头里不应该出现原始未清洗的路径参数痕迹。
    clean_export = client.get(f"/api/insurer-portal/settlement/monthly/{month}/export", headers=headers)
    assert clean_export.status_code == 200
    disposition = clean_export.headers["content-disposition"]
    assert "\n" not in disposition and "\t" not in disposition
    assert f"premium-{month}.xlsx" in disposition


def run():
    test_insurer_settlement_scoped_and_hides_profit()
    print("test_insurer_settlement_scoped_and_hides_profit: OK")
    test_settlement_total_cumulative_premium_reflects_real_headcount()
    print("test_settlement_total_cumulative_premium_reflects_real_headcount: OK")
    test_settlement_cumulative_includes_already_stopped_people()
    print("test_settlement_cumulative_includes_already_stopped_people: OK")
    test_monthly_premium_summary_detail_export_scoped()
    print("test_monthly_premium_summary_detail_export_scoped: OK")
    print("\nAll insurer settlement tests: PASS")


if __name__ == "__main__":
    run()
