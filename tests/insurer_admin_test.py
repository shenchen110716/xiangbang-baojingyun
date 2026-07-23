"""Admin 保司管理: CRUD, pending-edit two-stage approval, merge tool."""
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
from backend.models import Insurer, InsurancePlan, User  # noqa: E402

startup()
client = TestClient(app)


def _admin_token():
    with SessionLocal() as s:
        if not s.query(User).filter(User.username == "admin_insurer_test").first():
            s.add(User(username="admin_insurer_test", password_hash=pwd.hash("admin1234"), name="平台", role="admin"))
            s.commit()
    resp = client.post("/api/auth/login", json={"username": "admin_insurer_test", "password": "admin1234", "portal": "admin"})
    return resp.json()["access_token"]


def test_create_and_list_insurer():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    resp = client.post("/api/insurers", json={"name": "太平洋保险", "contact": "李经理", "phone": "13900000000"}, headers=headers)
    assert resp.status_code == 200
    listing = client.get("/api/insurers", headers=headers)
    assert any(x["name"] == "太平洋保险" for x in listing.json())


def test_pending_edit_two_stage_approval():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    created = client.post("/api/insurers", json={"name": "人寿保险", "contact": "老联系人", "phone": "111"}, headers=headers).json()
    insurer_id = created["id"]
    with SessionLocal() as s:
        item = s.get(Insurer, insurer_id)
        item.pending_name = "人寿保险(改名)"
        item.pending_contact = "新联系人"
        item.pending_phone = "222"
        from datetime import datetime, timezone
        item.pending_submitted_at = datetime.now(timezone.utc)
        s.commit()
    unchanged = client.get("/api/insurers", headers=headers).json()
    row = next(x for x in unchanged if x["id"] == insurer_id)
    assert row["name"] == "人寿保险"
    assert row["pending_name"] == "人寿保险(改名)"
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": True}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "人寿保险(改名)"
    assert resp.json()["pending_name"] is None


def test_pending_edit_reject_leaves_name_unchanged():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    created = client.post("/api/insurers", json={"name": "友邦保险"}, headers=headers).json()
    insurer_id = created["id"]
    with SessionLocal() as s:
        item = s.get(Insurer, insurer_id)
        item.pending_name = "友邦保险(错误改名)"
        from datetime import datetime, timezone
        item.pending_submitted_at = datetime.now(timezone.utc)
        s.commit()
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": False, "reject_reason": "信息有误"}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "友邦保险"
    assert resp.json()["pending_name"] is None


def test_merge_insurers_repoints_plans():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    a = client.post("/api/insurers", json={"name": "人保"}, headers=headers).json()
    b = client.post("/api/insurers", json={"name": "人保保险"}, headers=headers).json()
    with SessionLocal() as s:
        plan = InsurancePlan(insurer="人保保险", name="测试方案", insurer_id=b["id"])
        s.add(plan); s.commit(); s.refresh(plan)
        plan_id = plan.id
    resp = client.post("/api/insurers/merge", json={"source_ids": [b["id"]], "target_id": a["id"]}, headers=headers)
    assert resp.status_code == 200
    with SessionLocal() as s:
        assert s.get(InsurancePlan, plan_id).insurer_id == a["id"]
        assert s.get(Insurer, b["id"]) is None


def run():
    test_create_and_list_insurer()
    print("test_create_and_list_insurer: OK")
    test_pending_edit_two_stage_approval()
    print("test_pending_edit_two_stage_approval: OK")
    test_pending_edit_reject_leaves_name_unchanged()
    print("test_pending_edit_reject_leaves_name_unchanged: OK")
    test_merge_insurers_repoints_plans()
    print("test_merge_insurers_repoints_plans: OK")
    print("\nAll insurer admin tests: PASS")


if __name__ == "__main__":
    run()
