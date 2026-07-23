"""End-to-end insurer-portal isolation sweep: two insurers, two enterprises,
one of each module's record type, assert every cross-insurer read/write is
blocked. This complements the per-module scope tests in Tasks 6-11 with one
smoke test that exercises the same login -> act -> verify path a real insurer
account would take across all seven modules in one run."""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app, startup  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import (  # noqa: E402
    ActualEmployer, Claim, Enterprise, Insurer, InsurancePlan, InsuredPerson,
    Invoice, Policy, User, WorkPosition,
)

startup()
client = TestClient(app)


def _build_world():
    with SessionLocal() as s:
        insurer_a = Insurer(name="全链路保司A")
        insurer_b = Insurer(name="全链路保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="全链路保司B", name="B方案", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise = Enterprise(name="全链路企业")
        s.add(enterprise); s.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="全链路用工单位")
        s.add(employer); s.flush()
        position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                   actual_employer=employer.name, name="B岗位", occupation_class="1-3类",
                                   plan_id=plan_b.id, status="approved")
        s.add(position_b); s.flush()
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="B员工", id_number="340123199001019999",
                                  position_id=position_b.id, status="active")
        policy_b = Policy(policy_no="POL-FULL-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(person_b); s.add(policy_b); s.flush()
        person_b.policy_id = policy_b.id
        invoice_b = Invoice(enterprise_id=enterprise.id, account="premium", amount=100)
        claim_b = Claim(enterprise_id=enterprise.id, person_id=person_b.id, policy_id=policy_b.id,
                        claim_no="CLM-FULL-B", status="insurer_review", current_handler="保险公司理赔岗")
        s.add(invoice_b); s.add(claim_b); s.flush()
        user_a = User(username="full_isolation_insurer_a", password_hash=pwd.hash("test1234"),
                     name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return {
            "position_b": position_b.id, "person_b": person_b.id, "policy_b": policy_b.id,
            "claim_b": claim_b.id,
        }


def test_insurer_a_touches_nothing_belonging_to_insurer_b():
    ids = _build_world()
    login = client.post("/api/auth/login", json={"username": "full_isolation_insurer_a", "password": "test1234", "portal": "insurer"})
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    positions = client.get("/api/positions", headers=headers).json()
    assert all(row["id"] != ids["position_b"] for row in positions)

    policies = client.get("/api/policies", headers=headers).json()
    assert all(row["id"] != ids["policy_b"] for row in policies)

    invoices = client.get("/api/invoices", headers=headers).json()
    assert invoices == []

    insured = client.get("/api/insurer-portal/insured", headers=headers).json()
    assert all(row["id"] != ids["person_b"] for row in insured)

    claims = client.get("/api/claims", headers=headers).json()
    assert all(row["id"] != ids["claim_b"] for row in claims)

    settlement = client.get("/api/insurer-portal/settlement", headers=headers).json()
    assert all(row["policy_id"] != ids["policy_b"] for row in settlement["rows"])

    flag_resp = client.patch(f"/api/insured/{ids['person_b']}/insurer-flag", json={"reason": "x"}, headers=headers)
    assert flag_resp.status_code == 403

    claim_resp = client.patch(f"/api/claims/{ids['claim_b']}/status", json={"status": "approved", "approved_amount": 1}, headers=headers)
    assert claim_resp.status_code == 403

    review_resp = client.patch(f"/api/positions/{ids['position_b']}/review",
                               json={"status": "approved", "occupation_class": "1-3类"}, headers=headers)
    assert review_resp.status_code in (400, 403)

    upload_resp = client.post(f"/api/policies/{ids['policy_b']}/document/upload", headers=headers,
                              files={"file": ("t.pdf", b"%PDF fake", "application/pdf")})
    assert upload_resp.status_code == 403


if __name__ == "__main__":
    test_insurer_a_touches_nothing_belonging_to_insurer_b()
    print("insurer_full_isolation_smoke: OK")
