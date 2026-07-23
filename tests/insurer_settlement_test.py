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
from backend.models import Enterprise, Insurer, InsurancePlan, Policy, User  # noqa: E402

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


def run():
    test_insurer_settlement_scoped_and_hides_profit()
    print("test_insurer_settlement_scoped_and_hides_profit: OK")
    print("\nAll insurer settlement tests: PASS")


if __name__ == "__main__":
    run()
