"""理赔管理: insurer can only act on own-insurer claims already at insurer_review,
cannot see earlier-stage claims, cannot skip straight to paid/closed."""
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
from backend.models import Claim, Enterprise, Insurer, InsurancePlan, InsuredPerson, Policy, User  # noqa: E402

startup()
client = TestClient(app)

_seq = 0


def _setup(claim_a_status="insurer_review"):
    # 每个测试函数各自独立调用 _setup()，但共用同一个进程级 sqlite 文件——
    # policy_no/claim_no/username 都有唯一约束，所以这里给每次调用的记录名
    # 加上自增后缀，避免第二个测试函数因为撞名而触发 UNIQUE constraint failed。
    global _seq
    _seq += 1
    n = _seq
    with SessionLocal() as s:
        insurer_a = Insurer(name=f"理赔保司A{n}"); insurer_b = Insurer(name=f"理赔保司B{n}")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer=f"理赔保司A{n}", name=f"方案A{n}", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer=f"理赔保司B{n}", name=f"方案B{n}", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name=f"理赔测试企业{n}"); s.add(enterprise); s.flush()
        policy_a = Policy(policy_no=f"POL-CLAIM-A{n}", enterprise_id=enterprise.id, plan_id=plan_a.id, premium=100)
        policy_b = Policy(policy_no=f"POL-CLAIM-B{n}", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(policy_a); s.add(policy_b); s.flush()
        person_a = InsuredPerson(enterprise_id=enterprise.id, name="王五", id_number=f"34012319900101{1236+n:04d}", status="active", policy_id=policy_a.id)
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="赵六", id_number=f"34012319900101{1500+n:04d}", status="active", policy_id=policy_b.id)
        s.add(person_a); s.add(person_b); s.flush()
        claim_a = Claim(enterprise_id=enterprise.id, person_id=person_a.id, policy_id=policy_a.id,
                        claim_no=f"CLM-A{n}", status=claim_a_status, current_handler="保险公司理赔岗")
        claim_b = Claim(enterprise_id=enterprise.id, person_id=person_b.id, policy_id=policy_b.id,
                        claim_no=f"CLM-B{n}", status="insurer_review", current_handler="保险公司理赔岗")
        claim_early = Claim(enterprise_id=enterprise.id, person_id=person_a.id, policy_id=policy_a.id,
                            claim_no=f"CLM-EARLY{n}", status="collecting", current_handler="企业经办人")
        s.add(claim_a); s.add(claim_b); s.add(claim_early); s.flush()
        username = f"claim_insurer_a{n}"
        user_a = User(username=username, password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return claim_a.id, claim_b.id, claim_early.id, username


def _headers(username):
    login = client.post("/api/auth/login", json={"username": username, "password": "test1234", "portal": "insurer"})
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


def test_insurer_sees_only_own_insurer_review_stage_claims():
    claim_a_id, claim_b_id, claim_early_id, username = _setup()
    resp = client.get("/api/claims", headers=_headers(username))
    assert resp.status_code == 200
    ids = {row["id"] for row in resp.json()}
    assert claim_a_id in ids
    assert claim_b_id not in ids
    assert claim_early_id not in ids


def test_insurer_can_approve_own_claim_at_insurer_review():
    claim_a_id, claim_b_id, claim_early_id, username = _setup()
    resp = client.patch(f"/api/claims/{claim_a_id}/status", json={"status": "approved", "approved_amount": 5000}, headers=_headers(username))
    assert resp.status_code == 200
    assert resp.json()["status"] == "approved"


def test_insurer_cannot_approve_other_insurer_claim():
    claim_a_id, claim_b_id, claim_early_id, username = _setup()
    resp = client.patch(f"/api/claims/{claim_b_id}/status", json={"status": "approved", "approved_amount": 5000}, headers=_headers(username))
    assert resp.status_code == 403


def test_insurer_cannot_act_on_early_stage_claim():
    claim_a_id, claim_b_id, claim_early_id, username = _setup()
    resp = client.patch(f"/api/claims/{claim_early_id}/status", json={"status": "submitted"}, headers=_headers(username))
    assert resp.status_code == 403


def test_insurer_cannot_skip_to_paid():
    claim_a_id, claim_b_id, claim_early_id, username = _setup()
    resp = client.patch(f"/api/claims/{claim_a_id}/status", json={"status": "paid"}, headers=_headers(username))
    assert resp.status_code in (403, 409)


def test_insurer_cannot_act_on_approved_claim_even_if_transition_allowed():
    """Prove insurer restriction is independent of CLAIM_TRANSITIONS table.

    CLAIM_TRANSITIONS['approved'] permits 'approved'→'paid' for any role.
    _INSURER_VISIBLE_CLAIM_STATUSES includes 'approved', so insurer can read it.
    But insurer-specific check requires item.status=='insurer_review' to ACT.
    This test verifies that restriction fires independently: 403 must come
    from the insurer check, not from the general transition table.
    """
    claim_a_id, claim_b_id, claim_early_id, username = _setup(claim_a_status="approved")
    resp = client.patch(f"/api/claims/{claim_a_id}/status", json={"status": "paid"}, headers=_headers(username))
    assert resp.status_code == 403


def run():
    test_insurer_sees_only_own_insurer_review_stage_claims()
    print("test_insurer_sees_only_own_insurer_review_stage_claims: OK")
    test_insurer_can_approve_own_claim_at_insurer_review()
    print("test_insurer_can_approve_own_claim_at_insurer_review: OK")
    test_insurer_cannot_approve_other_insurer_claim()
    print("test_insurer_cannot_approve_other_insurer_claim: OK")
    test_insurer_cannot_act_on_early_stage_claim()
    print("test_insurer_cannot_act_on_early_stage_claim: OK")
    test_insurer_cannot_skip_to_paid()
    print("test_insurer_cannot_skip_to_paid: OK")
    test_insurer_cannot_act_on_approved_claim_even_if_transition_allowed()
    print("test_insurer_cannot_act_on_approved_claim_even_if_transition_allowed: OK")
    print("\nAll insurer claims scope tests: PASS")


if __name__ == "__main__":
    run()
