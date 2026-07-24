"""Admin 保司管理: CRUD, pending-edit two-stage approval, merge tool."""
import os
import sys
import tempfile
from pathlib import Path

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import select  # noqa: E402

from backend.app import app, startup  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Insurer, InsurancePlan, User, InsurerAccountLink, InsurerAccount  # noqa: E402

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


def test_create_insurer_with_credit_code_email_address():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    resp = client.post("/api/insurers", json={
        "name": "字段测试保司", "credit_code": "91320100MA1YYYYY2Y", "email": "biz@example.com", "address": "苏州市工业园区",
    }, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["credit_code"] == "91320100MA1YYYYY2Y"
    assert body["email"] == "biz@example.com"
    assert body["address"] == "苏州市工业园区"

    update = client.patch(f"/api/insurers/{body['id']}", json={"email": "new@example.com"}, headers=headers)
    assert update.status_code == 200
    assert update.json()["email"] == "new@example.com"
    assert update.json()["credit_code"] == "91320100MA1YYYYY2Y"


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
        item.pending_email = "wrong@example.com"
        from datetime import datetime, timezone
        item.pending_submitted_at = datetime.now(timezone.utc)
        s.commit()
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": False, "reject_reason": "信息有误"}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "友邦保险"
    assert resp.json()["pending_name"] is None
    assert resp.json()["email"] == ""
    assert resp.json()["pending_email"] is None


def test_merge_insurers_repoints_plans():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    a = client.post("/api/insurers", json={"name": "人保"}, headers=headers).json()
    b = client.post("/api/insurers", json={"name": "人保保险"}, headers=headers).json()
    with SessionLocal() as s:
        # Create InsurancePlan tied to source insurer
        plan = InsurancePlan(insurer="人保保险", name="测试方案", insurer_id=b["id"])
        s.add(plan); s.commit(); s.refresh(plan)
        plan_id = plan.id

        # Create InsurerAccount and InsurerAccountLink tied to source insurer
        account = InsurerAccount(label="test_account", bank_name="工商银行", account_no="1234567890")
        s.add(account); s.commit(); s.refresh(account)
        account_link = InsurerAccountLink(insurer="人保保险", account_id=account.id, insurer_id=b["id"])
        s.add(account_link); s.commit(); s.refresh(account_link)
        link_id = account_link.id

        # Create User with role="insurer" tied to source insurer
        user = User(username="insurer_merge_test", password_hash=pwd.hash("test1234"), name="源保司用户", role="insurer", insurer_id=b["id"])
        s.add(user); s.commit(); s.refresh(user)
        user_id = user.id

    resp = client.post("/api/insurers/merge", json={"source_ids": [b["id"]], "target_id": a["id"]}, headers=headers)
    assert resp.status_code == 200
    with SessionLocal() as s:
        # Verify InsurancePlan re-pointed
        assert s.get(InsurancePlan, plan_id).insurer_id == a["id"]
        # Verify InsurerAccountLink re-pointed
        assert s.get(InsurerAccountLink, link_id).insurer_id == a["id"]
        # Verify User re-pointed
        assert s.get(User, user_id).insurer_id == a["id"]
        # Verify source insurer deleted
        assert s.get(Insurer, b["id"]) is None


def test_create_insurer_account_and_login():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    insurer = client.post("/api/insurers", json={"name": "账号测试保司"}, headers=headers).json()
    resp = client.post(f"/api/insurers/{insurer['id']}/accounts",
                       json={"username": "insurer_account_test", "password": "test1234", "name": "测试账号"},
                       headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["username"] == "insurer_account_test"
    with SessionLocal() as s:
        user = s.scalar(select(User).where(User.username == "insurer_account_test"))
        assert user.role == "insurer"
        assert user.insurer_id == insurer["id"]
    login = client.post("/api/auth/login", json={"username": "insurer_account_test", "password": "test1234", "portal": "insurer"})
    assert login.status_code == 200

    listing = client.get(f"/api/insurers/{insurer['id']}/accounts", headers=headers)
    assert any(x["username"] == "insurer_account_test" for x in listing.json())


def test_create_insurer_account_duplicate_username_rejected():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    insurer = client.post("/api/insurers", json={"name": "重复账号测试保司"}, headers=headers).json()
    client.post(f"/api/insurers/{insurer['id']}/accounts", json={"username": "dup_insurer_account", "password": "test1234"}, headers=headers)
    resp = client.post(f"/api/insurers/{insurer['id']}/accounts", json={"username": "dup_insurer_account", "password": "test1234"}, headers=headers)
    assert resp.status_code == 409


def test_pause_insurer_account_blocks_login():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    insurer = client.post("/api/insurers", json={"name": "暂停测试保司"}, headers=headers).json()
    account = client.post(f"/api/insurers/{insurer['id']}/accounts", json={"username": "pause_insurer_account", "password": "test1234"}, headers=headers).json()
    resp = client.patch(f"/api/insurers/accounts/{account['id']}/status", params={"status": "paused"}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["status"] == "paused"
    login = client.post("/api/auth/login", json={"username": "pause_insurer_account", "password": "test1234", "portal": "insurer"})
    assert login.status_code == 403


def test_reset_insurer_account_password():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    insurer = client.post("/api/insurers", json={"name": "重置密码测试保司"}, headers=headers).json()
    account = client.post(f"/api/insurers/{insurer['id']}/accounts", json={"username": "reset_pw_insurer_account", "password": "old12345"}, headers=headers).json()

    old_login = client.post("/api/auth/login", json={"username": "reset_pw_insurer_account", "password": "old12345", "portal": "insurer"})
    assert old_login.status_code == 200
    old_token = old_login.json()["access_token"]
    # old token demonstrably valid before the reset, so a later 401 is caused by the reset itself
    assert client.get("/api/auth/me", headers={"Authorization": f"Bearer {old_token}"}).status_code == 200

    reset = client.post(f"/api/insurers/accounts/{account['id']}/reset-password", json={"password": "new67890"}, headers=headers)
    assert reset.status_code == 200

    # old password no longer works
    old_password_login = client.post("/api/auth/login", json={"username": "reset_pw_insurer_account", "password": "old12345", "portal": "insurer"})
    assert old_password_login.status_code == 401

    # new password works
    new_login = client.post("/api/auth/login", json={"username": "reset_pw_insurer_account", "password": "new67890", "portal": "insurer"})
    assert new_login.status_code == 200

    # any token issued before the reset is now invalidated (session_version bump)
    stale_me = client.get("/api/auth/me", headers={"Authorization": f"Bearer {old_token}"})
    assert stale_me.status_code == 401


def run():
    test_create_and_list_insurer()
    print("test_create_and_list_insurer: OK")
    test_create_insurer_with_credit_code_email_address()
    print("test_create_insurer_with_credit_code_email_address: OK")
    test_pending_edit_two_stage_approval()
    print("test_pending_edit_two_stage_approval: OK")
    test_pending_edit_reject_leaves_name_unchanged()
    print("test_pending_edit_reject_leaves_name_unchanged: OK")
    test_merge_insurers_repoints_plans()
    print("test_merge_insurers_repoints_plans: OK")
    test_create_insurer_account_and_login()
    print("test_create_insurer_account_and_login: OK")
    test_create_insurer_account_duplicate_username_rejected()
    print("test_create_insurer_account_duplicate_username_rejected: OK")
    test_pause_insurer_account_blocks_login()
    print("test_pause_insurer_account_blocks_login: OK")
    test_reset_insurer_account_password()
    print("test_reset_insurer_account_password: OK")
    print("\nAll insurer admin tests: PASS")


if __name__ == "__main__":
    run()
