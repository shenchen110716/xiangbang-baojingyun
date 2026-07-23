"""Insurer 基本信息编辑: submit writes pending_* only, admin approve/reject."""
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
from backend.models import Insurer, User  # noqa: E402

startup()
client = TestClient(app)


def _insurer_token(name="平安保险", username="insurer_profile_test"):
    with SessionLocal() as s:
        insurer = Insurer(name=name)
        s.add(insurer); s.flush()
        s.add(User(username=username, password_hash=pwd.hash("test1234"), name="保司账号", role="insurer", insurer_id=insurer.id))
        s.commit()
        insurer_id = insurer.id
    resp = client.post("/api/auth/login", json={"username": username, "password": "test1234", "portal": "insurer"})
    return resp.json()["access_token"], insurer_id


def test_submit_edit_writes_pending_only():
    token, insurer_id = _insurer_token()
    headers = {"Authorization": f"Bearer {token}"}
    resp = client.patch("/api/insurer-portal/profile", json={"name": "平安保险(新)"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "平安保险"
    assert body["pending_name"] == "平安保险(新)"


def test_admin_approve_commits_change():
    token, insurer_id = _insurer_token(name="太平人寿", username="insurer_profile_test2")
    headers = {"Authorization": f"Bearer {token}"}
    client.patch("/api/insurer-portal/profile", json={"name": "太平人寿(新)"}, headers=headers)
    with SessionLocal() as s:
        if not s.query(User).filter(User.username == "admin_profile_test").first():
            s.add(User(username="admin_profile_test", password_hash=pwd.hash("admin1234"), name="平台", role="admin"))
            s.commit()
    admin_login = client.post("/api/auth/login", json={"username": "admin_profile_test", "password": "admin1234", "portal": "admin"})
    admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": True}, headers=admin_headers)
    assert resp.json()["name"] == "太平人寿(新)"
    assert resp.json()["pending_name"] is None


def run():
    test_submit_edit_writes_pending_only()
    print("test_submit_edit_writes_pending_only: OK")
    test_admin_approve_commits_change()
    print("test_admin_approve_commits_change: OK")
    print("\nAll insurer profile tests: PASS")


if __name__ == "__main__":
    run()
