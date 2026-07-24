"""员工参停保异常标注: insurer can flag/clear own-scope people, not other-insurer people, and never touches status."""
import os
import sys
import tempfile
from pathlib import Path

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import select  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app, startup  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import ActualEmployer, Enterprise, Insurer, InsurancePlan, InsuredPerson, User, WorkPosition  # noqa: E402

startup()
client = TestClient(app)


def _setup():
    # Each test function below calls _setup() independently against the same
    # sqlite file (DATABASE_URL is fixed for the whole process), so this is
    # idempotent: it creates the fixture data (and a fresh pair of
    # InsuredPerson rows to flag) on first call, then reuses the same
    # insurer/enterprise/user records on subsequent calls rather than
    # colliding on the unique username.
    with SessionLocal() as s:
        insurer_a = s.scalar(select(Insurer).where(Insurer.name == "标注保司A"))
        if not insurer_a:
            insurer_a = Insurer(name="标注保司A"); insurer_b = Insurer(name="标注保司B")
            s.add(insurer_a); s.add(insurer_b); s.flush()
            plan_a = InsurancePlan(insurer="标注保司A", name="方案A", insurer_id=insurer_a.id)
            plan_b = InsurancePlan(insurer="标注保司B", name="方案B", insurer_id=insurer_b.id)
            s.add(plan_a); s.add(plan_b); s.flush()
            enterprise = Enterprise(name="标注测试企业"); s.add(enterprise); s.flush()
            employer = ActualEmployer(enterprise_id=enterprise.id, name="标注用工单位"); s.add(employer); s.flush()
            position_a = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                       name="岗位A", occupation_class="1-3类", plan_id=plan_a.id, status="approved")
            position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                       name="岗位B", occupation_class="1-3类", plan_id=plan_b.id, status="approved")
            s.add(position_a); s.add(position_b); s.flush()
            user_a = User(username="flag_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
            s.add(user_a); s.commit()
        else:
            position_a = s.scalar(select(WorkPosition).where(WorkPosition.name == "岗位A"))
            position_b = s.scalar(select(WorkPosition).where(WorkPosition.name == "岗位B"))
            enterprise = s.scalar(select(Enterprise).where(Enterprise.name == "标注测试企业"))
        person_a = InsuredPerson(enterprise_id=enterprise.id, name="张三", id_number=f"340123199001{s.query(InsuredPerson).count():06d}",
                                  position_id=position_a.id, status="active")
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="李四", id_number=f"340123199002{s.query(InsuredPerson).count():06d}",
                                  position_id=position_b.id, status="active")
        s.add(person_a); s.add(person_b); s.commit()
        return person_a.id, person_b.id


def test_insurer_can_flag_own_scope_person():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": "保单信息与员工不符"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["insurer_flag_reason"] == "保单信息与员工不符"
    assert body["status"] == "active"


def test_insurer_cannot_flag_other_insurer_person():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/insured/{person_b_id}/insurer-flag", json={"reason": "越权测试"}, headers=headers)
    assert resp.status_code == 403


def test_clear_flag_by_empty_reason():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": "有问题"}, headers=headers)
    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": ""}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["insurer_flag_reason"] == ""


def test_flag_never_changes_status_or_clears_flagged_metadata():
    person_a_id, _ = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": "有问题"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "active"
    assert body["insurer_flagged_at"] is not None
    assert body["insurer_flagged_by"] is not None

    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": ""}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "active"
    assert body["insurer_flag_reason"] == ""
    assert body["insurer_flagged_at"] is None
    assert body["insurer_flagged_by"] is None


if __name__ == "__main__":
    test_insurer_can_flag_own_scope_person()
    test_insurer_cannot_flag_other_insurer_person()
    test_clear_flag_by_empty_reason()
    test_flag_never_changes_status_or_clears_flagged_metadata()
    print("insurer_flag_test.py: all tests passed")
