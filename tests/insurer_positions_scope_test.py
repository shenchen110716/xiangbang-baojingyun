"""岗位核保范围隔离: insurer A cannot review/see insurer B's positions."""
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
from backend.models import ActualEmployer, Enterprise, Insurer, InsurancePlan, User, WorkPosition  # noqa: E402

startup()
client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        # 幂等：run() 在同一个 SQLite 文件里多次调用 _setup()，"保司A" 的用户账号
        # 只创建一次（下面的 if not ... 判断），所以保司A/保司B 这两条 Insurer
        # 记录也必须复用同一条，否则新一轮创建出的 plan_a 会绑到一个新的 insurer_a
        # id 上，和已存在、绑定着旧 insurer_a id 的 scope_insurer_a 账号对不上。
        insurer_a = s.query(Insurer).filter(Insurer.name == "保司A").first() or Insurer(name="保司A")
        insurer_b = s.query(Insurer).filter(Insurer.name == "保司B").first() or Insurer(name="保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="保司A", name="方案A", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="测试企业"); s.add(enterprise); s.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位"); s.add(employer); s.flush()
        position_a = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                   name="岗位A", occupation_class="1-3类", plan_id=plan_a.id, status="approved")
        position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                   name="岗位B", occupation_class="1-3类", plan_id=plan_b.id, status="approved")
        s.add(position_a); s.add(position_b); s.flush()
        if not s.query(User).filter(User.username == "scope_insurer_a").first():
            s.add(User(username="scope_insurer_a", password_hash=pwd.hash("test1234"), name="保司A账号", role="insurer", insurer_id=insurer_a.id))
        s.commit()
        return plan_a.id, plan_b.id, position_a.id, position_b.id


def test_insurer_cannot_see_other_insurer_position():
    plan_a_id, plan_b_id, position_a_id, position_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/positions", headers=headers)
    assert resp.status_code == 200
    assert all(row["id"] != position_b_id for row in resp.json())
    assert any(row["id"] == position_a_id for row in resp.json())


def test_insurer_cannot_review_position_into_other_insurer_plan():
    plan_a_id, plan_b_id, position_a_id, position_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/positions/{position_b_id}/review",
                        json={"status": "approved", "occupation_class": "1-3类", "plan_id": plan_b_id},
                        headers=headers)
    assert resp.status_code == 403


def test_insurer_can_view_own_position_videos_not_others():
    plan_a_id, plan_b_id, position_a_id, position_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    own = client.get(f"/api/positions/{position_a_id}/videos", headers=headers)
    assert own.status_code == 200
    other = client.get(f"/api/positions/{position_b_id}/videos", headers=headers)
    assert other.status_code == 403


def run():
    test_insurer_cannot_see_other_insurer_position()
    print("test_insurer_cannot_see_other_insurer_position: OK")
    test_insurer_cannot_review_position_into_other_insurer_plan()
    print("test_insurer_cannot_review_position_into_other_insurer_plan: OK")
    test_insurer_can_view_own_position_videos_not_others()
    print("test_insurer_can_view_own_position_videos_not_others: OK")
    print("\nAll insurer positions scope tests: PASS")


if __name__ == "__main__":
    run()
