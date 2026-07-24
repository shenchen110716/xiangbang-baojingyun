"""保单上传范围隔离: insurer cannot see or upload to another insurer's policy."""
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
        insurer_a = Insurer(name="保单保司A"); insurer_b = Insurer(name="保单保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="保单保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise = Enterprise(name="保单测试企业"); s.add(enterprise); s.flush()
        policy_b = Policy(policy_no=f"POL-SCOPE-B-{plan_b.id}", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(policy_b); s.flush()
        if not s.query(User).filter(User.username == "scope_policy_insurer_a").first():
            s.add(User(username="scope_policy_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id))
        s.commit()
        return policy_b.id


def test_insurer_cannot_see_other_insurer_policy():
    policy_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_policy_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/policies", headers=headers)
    assert resp.status_code == 200
    assert all(row["id"] != policy_b_id for row in resp.json())


def test_insurer_cannot_upload_to_other_insurer_policy():
    policy_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_policy_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.post(f"/api/policies/{policy_b_id}/document/upload", headers=headers,
                       files={"file": ("test.pdf", b"%PDF-1.4 fake", "application/pdf")})
    assert resp.status_code == 403


def run():
    test_insurer_cannot_see_other_insurer_policy()
    print("test_insurer_cannot_see_other_insurer_policy: OK")
    test_insurer_cannot_upload_to_other_insurer_policy()
    print("test_insurer_cannot_upload_to_other_insurer_policy: OK")
    print("\nAll insurer policy upload scope tests: PASS")


if __name__ == "__main__":
    run()
