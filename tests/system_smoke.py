"""Isolated backend smoke test for critical permissions and money workflows."""
import os
import sys
import tempfile
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
        from backend.models import AgentCommission, User, WorkPosition
        from backend.schemas import (
            ActualEmployerIn, ActualEmployerUpdate, AgentIn,
            CommissionIn, CommissionUpdate, EnterpriseIn, InvoiceIn,
            InvoiceUpdate, OperatorIn, PaymentCallbackIn, PaymentIn,
            PasswordChangeIn, PersonIn, PlanIn, PositionIn,
        )
        from backend.services import commission_dict
        from backend.routers.agents import add_agent, add_agent_commission, update_agent_commission
        from backend.routers.auth import change_password
        from backend.routers.audit_logs import audit_logs
        from backend.routers.dashboard import dashboard, screen_products
        from backend.routers.enrollment import enrollment_email
        from backend.routers.enterprises import add_enterprise
        from backend.routers.insured import add_person
        from backend.routers.invoices import create_invoice, update_invoice
        from backend.routers.operators import add_operator
        from backend.routers.payments import create_payment, payment_callback
        from backend.routers.plans import add_plan
        from backend.routers.positions import (
            add_actual_employer, add_position, delete_actual_employer, update_actual_employer,
        )
        from backend.routers.reports import billing

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
            assert dashboard(user, session)["active_people"] == 1
            assert next(row for row in screen_products(user, session) if row["plan_id"] == plan["id"])["insured_count"] == 1
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
