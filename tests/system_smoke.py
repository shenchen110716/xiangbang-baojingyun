"""Isolated backend smoke test for critical permissions and money workflows."""
import json
import os
import sys
import tempfile
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import AgentCommission, Policy, PolicyMember, User, WorkPosition
        from backend.schemas import (
            ActualEmployerIn, ActualEmployerUpdate, AgentIn,
            ClaimDocumentIn, ClaimIn, ClaimStatusIn,
            CommissionIn, CommissionUpdate, EnterpriseIn, InvoiceIn,
            InvoiceUpdate, OperatorIn, PaymentCallbackIn, PaymentIn,
            PasswordChangeIn, PersonIn, PersonUpdate, PlanIn, PositionIn,
        )
        from backend.services import commission_dict
        from backend.services.claims import CLAIM_REQUIRED_TYPES
        from backend.routers.agents import add_agent, add_agent_commission, update_agent_commission
        from backend.routers.auth import change_password
        from backend.routers.audit_logs import audit_logs
        from backend.routers.claims import add_claim, add_claim_document, claim_status
        from backend.routers.dashboard import dashboard, screen_products
        from backend.routers.enrollment import enrollment_email
        from backend.routers.enterprises import add_enterprise
        from backend.routers.insured import add_person, insured_status, update_person
        from backend.routers.invoices import create_invoice, update_invoice
        from backend.routers.operators import add_operator
        from backend.routers.payments import create_payment, payment_callback
        from backend.routers.plans import add_plan
        from backend.routers.positions import (
            add_actual_employer, add_position, delete_actual_employer, update_actual_employer,
        )
        from backend.routers.reports import _period_premium, billing, premium_details
        from backend.routers.reports import export_policy
        from backend.routers.reports import policies as list_policies

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.username == "admin"))
            enterprise = add_enterprise(EnterpriseIn(name="冒烟测试企业"), admin, session)
            enterprise_id = enterprise["id"]
            operator = add_operator(OperatorIn(enterprise_id=enterprise_id, username="smoke_operator", password="smoke123", name="测试操作员"), admin, session)
            user = session.get(User, operator["id"])

            try:
                add_plan(PlanIn(insurer="测试保司", name="越权方案", price=1), user, session)
                raise AssertionError("enterprise plan creation should be forbidden")
            except HTTPException as error:
                assert error.status_code == 403

            employer = add_actual_employer(ActualEmployerIn(name="实际工作单位 A"), user, session)
            employer_id = employer["id"]
            updated = update_actual_employer(employer_id, ActualEmployerUpdate(contact="联系人"), user, session)
            assert updated["contact"] == "联系人"

            plan = add_plan(PlanIn(insurer="测试保司", insurer_email="claim@example.com", name="即时方案", price=100, commission_rate=.2, profit_amount=10, effective_mode="immediate", billing_mode="monthly"), admin, session)
            assert plan["billing_mode"] == "daily"
            assert plan["policy_floor_price"] == 80 and plan["minimum_sale_price"] == 90 and plan["total_commission_amount"] == 20
            agent = add_agent(AgentIn(username="smoke_agent", password="agent123", name="测试业务员"), admin, session)
            relation = add_agent_commission(CommissionIn(agent_id=agent["id"], enterprise_id=enterprise_id, plan_id=plan["id"], rate=.1, mode="rebate"), admin, session)
            relation_row = commission_dict(session.get(AgentCommission, relation["id"]), session)
            assert relation_row["agent_commission_amount"] == 10 and relation_row["sale_price"] == 90
            price_relation = update_agent_commission(relation["id"], CommissionUpdate(mode="price", sale_price=110), admin, session)
            assert price_relation["agent_commission_amount"] == 20 and price_relation["sale_price"] == 110

            position = add_position(PositionIn(enterprise_id=enterprise_id, actual_employer_id=employer_id, actual_employer="实际工作单位 A", name="测试岗位", occupation_class="1-3类", plan_id=plan["id"]), admin, session)
            position_row = session.get(WorkPosition, position["id"]); position_row.status="approved"; session.commit()
            person = add_person(PersonIn(enterprise_id=enterprise_id, name="测试员工", id_number="340123199001019999", position_id=position["id"]), user, session)
            added_at = person["created_at"]
            person = update_person(person["id"], PersonUpdate(effective_at="2026-01-02"), user, session)
            assert person["created_at"] == added_at
            assert person["effective_at"].date().isoformat() == "2026-01-02"
            assert person["status"] == "active"
            assert dashboard(user, session)["active_people"] == 1
            assert next(row for row in screen_products(user, session) if row["plan_id"] == plan["id"])["insured_count"] == 1

            # Entering a business effective date creates the PolicyMember bridge
            # and must not overwrite the employee record's operation timestamp.
            insured_status(person["id"], "active", user, session)
            member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == person["id"]))
            assert member is not None and member.status == "active" and member.terminated_at is None
            snapshot = json.loads(member.rate_snapshot_json)
            assert snapshot["sale_price"] == 110
            policy = session.get(Policy, member.policy_id)
            assert policy.enterprise_id == enterprise_id and policy.plan_id == plan["id"]

            rows = list_policies(user, session)
            assert len(rows) == 1 and rows[0]["insured_count"] == 1 and rows[0]["premium"] > 0
            export_policy(policy.id, user, session)  # must not raise

            # redundant active->active PATCH must not create a second PolicyMember
            count_before = session.query(PolicyMember).count()
            insured_status(person["id"], "active", user, session)
            assert session.query(PolicyMember).count() == count_before

            # stop then re-enroll must produce TWO separate coverage periods, not
            # overwrite the first one (SYSTEM-DESIGN-V4.md 16.2 "两个保障期间")
            insured_status(person["id"], "stopped", user, session)
            insured_status(person["id"], "active", user, session)
            members = session.scalars(select(PolicyMember).where(PolicyMember.person_id == person["id"]).order_by(PolicyMember.id)).all()
            assert len(members) == 2
            assert members[0].status == "terminated" and members[0].terminated_at is not None
            assert members[1].status == "active" and members[1].terminated_at is None

            premium_report = premium_details("2026-01-01", "2026-01-31", user, session)
            assert premium_report["detail_count"] == 1
            assert premium_report["rows"][0]["active_days"] == 30
            assert premium_report["total_premium"] == 3300
            assert round(_period_premium(31, "monthly", date(2026, 7, 14), date(2026, 7, 31)), 2) == 18
            assert round(_period_premium(31, "monthly", date(2026, 7, 14), date(2026, 8, 13)), 2) == 31

            # a person with no position/plan yet must activate without error and
            # simply skip Policy/PolicyMember creation (same permissiveness as today)
            unpositioned = add_person(PersonIn(enterprise_id=enterprise_id, name="待定岗位员工", id_number="340123199001019998"), user, session)
            insured_status(unpositioned["id"], "active", user, session)
            assert session.query(PolicyMember).filter_by(person_id=unpositioned["id"]).count() == 0

            # claim_status("submitted") must count already-uploaded required
            # documents without crashing (regression: a stray `x.status` in the
            # ClaimDocument query referenced the set-comprehension loop var
            # before it was bound, so every real submission 500'd).
            claim = add_claim(ClaimIn(enterprise_id=enterprise_id, person_id=person["id"], description="冒烟测试报案", accident_at="2026-01-01 09:00", accident_place="车间"), user, session)
            for doc_type in CLAIM_REQUIRED_TYPES:
                add_claim_document(claim["id"], ClaimDocumentIn(name=f"{doc_type}.pdf", doc_type=doc_type), user, session)
            submitted = claim_status(claim["id"], ClaimStatusIn(status="submitted"), user, session)
            assert submitted["status"] == "submitted"

            mail = enrollment_email(enterprise_id, plan["id"], "enrollment", "", user, session)
            assert mail["people_count"] == 1 and mail["filename"].endswith('.csv')

            invoice = create_invoice(InvoiceIn(enterprise_id=enterprise_id, account="premium", amount=88.5, title="冒烟测试企业"), user, session)
            reviewed = update_invoice(invoice["id"], InvoiceUpdate(status="issued"), admin, session)
            assert reviewed["status"] == "issued"

            before = billing(user, session)[0]["balance"]
            payment = create_payment(PaymentIn(enterprise_id=enterprise_id, account="premium", amount=25), user, session)
            callback = PaymentCallbackIn(order_no=payment["order_no"], status="paid")
            first = payment_callback(callback, session)
            second = payment_callback(callback, session)
            after = billing(user, session)[0]["balance"]
            assert first["idempotent"] is False and second["idempotent"] is True
            assert after - before == 25, (before, after)

            assert change_password(PasswordChangeIn(current_password="smoke123", new_password="smoke456"), user, session)["ok"]
            assert pwd.verify("smoke456", user.password_hash)
            unused = add_actual_employer(ActualEmployerIn(name="待删除工作单位"), user, session)
            assert delete_actual_employer(unused["id"], user, session)["ok"]
            logs = audit_logs(100, admin, session)
            assert any(item["object_type"] == "invoice" for item in logs)

    print("system smoke: ok")


if __name__ == "__main__":
    run()
