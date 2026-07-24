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
        plan_a = InsurancePlan(insurer="全链路保司A", name="A方案", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="全链路保司B", name="B方案", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        # Two separate enterprises: invoice visibility for an insurer is scoped by
        # *enterprise* (any enterprise holding a policy under the insurer's plans sees
        # all of that enterprise's invoices — see routers/invoices.py), so sharing one
        # enterprise between insurer A and insurer B would make B's invoice visible to
        # A's view for reasons unrelated to the bug this test guards against.
        enterprise_a = Enterprise(name="全链路企业A")
        enterprise_b = Enterprise(name="全链路企业B")
        s.add(enterprise_a); s.add(enterprise_b); s.flush()
        employer_a = ActualEmployer(enterprise_id=enterprise_a.id, name="全链路用工单位A")
        employer_b = ActualEmployer(enterprise_id=enterprise_b.id, name="全链路用工单位B")
        s.add(employer_a); s.add(employer_b); s.flush()
        position_a = WorkPosition(enterprise_id=enterprise_a.id, actual_employer_id=employer_a.id,
                                   actual_employer=employer_a.name, name="A岗位", occupation_class="1-3类",
                                   plan_id=plan_a.id, status="approved")
        position_b = WorkPosition(enterprise_id=enterprise_b.id, actual_employer_id=employer_b.id,
                                   actual_employer=employer_b.name, name="B岗位", occupation_class="1-3类",
                                   plan_id=plan_b.id, status="approved")
        s.add(position_a); s.add(position_b); s.flush()
        person_a = InsuredPerson(enterprise_id=enterprise_a.id, name="A员工", id_number="340123199001018888",
                                  position_id=position_a.id, status="active")
        person_b = InsuredPerson(enterprise_id=enterprise_b.id, name="B员工", id_number="340123199001019999",
                                  position_id=position_b.id, status="active")
        policy_a = Policy(policy_no="POL-FULL-A", enterprise_id=enterprise_a.id, plan_id=plan_a.id, premium=100)
        policy_b = Policy(policy_no="POL-FULL-B", enterprise_id=enterprise_b.id, plan_id=plan_b.id, premium=100)
        s.add(person_a); s.add(person_b); s.add(policy_a); s.add(policy_b); s.flush()
        person_a.policy_id = policy_a.id
        person_b.policy_id = policy_b.id
        invoice_a = Invoice(enterprise_id=enterprise_a.id, account="premium", amount=100)
        invoice_b = Invoice(enterprise_id=enterprise_b.id, account="premium", amount=100)
        claim_a = Claim(enterprise_id=enterprise_a.id, person_id=person_a.id, policy_id=policy_a.id,
                        claim_no="CLM-FULL-A", status="insurer_review", current_handler="保险公司理赔岗")
        claim_b = Claim(enterprise_id=enterprise_b.id, person_id=person_b.id, policy_id=policy_b.id,
                        claim_no="CLM-FULL-B", status="insurer_review", current_handler="保险公司理赔岗")
        s.add(invoice_a); s.add(invoice_b); s.add(claim_a); s.add(claim_b); s.flush()
        user_a = User(username="full_isolation_insurer_a", password_hash=pwd.hash("test1234"),
                     name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return {
            "position_a": position_a.id, "person_a": person_a.id, "policy_a": policy_a.id,
            "claim_a": claim_a.id, "invoice_a": invoice_a.id,
            "position_b": position_b.id, "person_b": person_b.id, "policy_b": policy_b.id,
            "claim_b": claim_b.id, "invoice_b": invoice_b.id,
        }


def test_insurer_a_touches_nothing_belonging_to_insurer_b():
    ids = _build_world()
    login = client.post("/api/auth/login", json={"username": "full_isolation_insurer_a", "password": "test1234", "portal": "insurer"})
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    positions = client.get("/api/positions", headers=headers).json()
    assert all(row["id"] != ids["position_b"] for row in positions)
    assert any(row["id"] == ids["position_a"] for row in positions)

    policies = client.get("/api/policies", headers=headers).json()
    assert all(row["id"] != ids["policy_b"] for row in policies)
    assert any(row["id"] == ids["policy_a"] for row in policies)

    invoices = client.get("/api/invoices", headers=headers).json()
    assert all(row["id"] != ids["invoice_b"] for row in invoices)
    assert any(row["id"] == ids["invoice_a"] for row in invoices)

    insured = client.get("/api/insurer-portal/insured", headers=headers).json()
    assert all(row["id"] != ids["person_b"] for row in insured)
    assert any(row["id"] == ids["person_a"] for row in insured)

    claims = client.get("/api/claims", headers=headers).json()
    assert all(row["id"] != ids["claim_b"] for row in claims)
    assert any(row["id"] == ids["claim_a"] for row in claims)

    settlement = client.get("/api/insurer-portal/settlement", headers=headers).json()
    assert all(row["policy_id"] != ids["policy_b"] for row in settlement["rows"])
    assert any(row["policy_id"] == ids["policy_a"] for row in settlement["rows"])

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

    export_resp = client.get(f"/api/policies/{ids['policy_b']}/export", headers=headers)
    assert export_resp.status_code == 403

    update_resp = client.patch(f"/api/claims/{ids['claim_a']}", json={"amount": 99999}, headers=headers)
    assert update_resp.status_code == 403

    delete_doc_resp = client.delete(f"/api/claims/{ids['claim_a']}/documents/1", headers=headers)
    assert delete_doc_resp.status_code == 403


if __name__ == "__main__":
    test_insurer_a_touches_nothing_belonging_to_insurer_b()
    print("insurer_full_isolation_smoke: OK")
