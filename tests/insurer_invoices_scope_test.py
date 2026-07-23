"""发票管理范围隔离: insurer only sees invoices for enterprises with a policy under its own plans."""
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
from backend.models import Enterprise, Insurer, InsurancePlan, Invoice, Policy, User  # noqa: E402

startup()
client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="发票保司A"); insurer_b = Insurer(name="发票保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="发票保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise_b = Enterprise(name="发票测试企业B")
        s.add(enterprise_b); s.flush()
        s.add(Policy(policy_no="POL-INV-B", enterprise_id=enterprise_b.id, plan_id=plan_b.id, premium=100))
        s.add(Invoice(enterprise_id=enterprise_b.id, account="premium", amount=100))
        s.flush()
        if not s.query(User).filter(User.username == "invoice_insurer_a").first():
            s.add(User(username="invoice_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id))
        s.commit()


def test_insurer_sees_no_invoices_for_other_insurers_enterprise():
    _setup()
    login = client.post("/api/auth/login", json={"username": "invoice_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/invoices", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == []


def run():
    test_insurer_sees_no_invoices_for_other_insurers_enterprise()
    print("test_insurer_sees_no_invoices_for_other_insurers_enterprise: OK")
    print("\nAll insurer invoices scope tests: PASS")


if __name__ == "__main__":
    run()
