"""Smoke test for the insurer-scoped recharge accounts feature (Phase A).

Isolated from tests/system_smoke.py on purpose: that file's PersonIn
fixture currently fails an unrelated ID-checksum validation bug, which
would make every scenario appended after it fail for reasons that have
nothing to do with this feature. This file builds its own minimal
fixtures instead of importing from system_smoke.py.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-recharge-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        import asyncio
        import io
        from sqlalchemy import select
        from fastapi import HTTPException, UploadFile

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.migrations import migrate_premium_balances
        from backend.models import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest, LedgerEntry, InsuredPerson, WorkPosition
        from backend.services.recharge import (
            resolve_account_for_insurer, insurers_for_account, insurer_account_dict,
            get_or_create_premium_account, premium_accounts_for_enterprise,
        )
        from backend.routers.insurer_accounts import (
            insurer_accounts, add_insurer_account, update_insurer_account,
            insurer_account_links, add_insurer_account_link, delete_insurer_account_link,
        )
        from backend.routers.recharge_requests import (
            create_recharge_request, list_recharge_requests, confirm_recharge_request, reject_recharge_request,
        )
        from backend.routers.enterprises import enterprise_premium_accounts
        from backend.routers.dashboard import dashboard as dashboard_endpoint
        from backend.routers.plans import add_plan
        from backend.routers.positions import add_position, add_actual_employer
        from backend.routers.insured import add_person, insured_status
        from backend.schemas import (
            InsurerAccountIn, InsurerAccountUpdate, InsurerAccountLinkIn,
            PlanIn, PositionIn, ActualEmployerIn, PersonIn,
        )

        startup()
        with SessionLocal() as session:
            account = InsurerAccount(label="测试账户", bank_name="测试银行", account_no="1234567890", account_holder="测试收款方", status="active")
            session.add(account); session.commit(); session.refresh(account)
            assert account.id is not None and account.status == "active"

            link = InsurerAccountLink(insurer="测试保司", account_id=account.id)
            session.add(link); session.commit(); session.refresh(link)
            assert link.account_id == account.id

            premium_account = EnterprisePremiumAccount(enterprise_id=1, account_id=account.id, balance=100.0)
            session.add(premium_account); session.commit(); session.refresh(premium_account)
            assert premium_account.balance == 100.0

            request = RechargeRequest(enterprise_id=1, account_type="premium", insurer="测试保司", account_id=account.id, amount=50.0, receipt_file_url="/uploads/x.png", status="pending", created_by=1)
            session.add(request); session.commit(); session.refresh(request)
            assert request.status == "pending" and request.confirmed_by is None

            entry = LedgerEntry(enterprise_id=1, account="premium", direction="credit", amount=50, business_type="test", account_id=account.id)
            session.add(entry); session.commit()
            reloaded = session.scalar(select(LedgerEntry).where(LedgerEntry.id == entry.id))
            assert reloaded.account_id == account.id

            from backend.models import Enterprise
            legacy_enterprise = Enterprise(name="历史余额企业", kind="企业", contact="", phone="", status="active", premium_balance=88.0)
            session.add(legacy_enterprise); session.commit(); session.refresh(legacy_enterprise)

            migrate_premium_balances(session)
            migrated = session.scalar(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == legacy_enterprise.id))
            assert migrated is not None and migrated.balance == 88.0
            placeholder_account = session.get(InsurerAccount, migrated.account_id)
            assert placeholder_account.label == "未分类（历史余额）"

            # idempotent: running it again must not create a second row
            migrate_premium_balances(session)
            count = len(session.scalars(select(EnterprisePremiumAccount).where(EnterprisePremiumAccount.enterprise_id == legacy_enterprise.id)).all())
            assert count == 1

            second_link = InsurerAccountLink(insurer="第二保司", account_id=account.id)
            session.add(second_link); session.commit()

            resolved = resolve_account_for_insurer(session, "测试保司")
            assert resolved is not None and resolved.id == account.id

            names = insurers_for_account(session, account.id)
            assert set(names) == {"测试保司", "第二保司"}

            account_payload = insurer_account_dict(account, session)
            assert set(account_payload["insurers"]) == {"测试保司", "第二保司"}

            fetched_again = get_or_create_premium_account(session, 1, account.id)
            assert fetched_again.id == premium_account.id  # get_or_create must not duplicate

            # exercise the actual "create" branch: no existing row for this (enterprise_id, account_id) pair
            created = get_or_create_premium_account(session, 2, account.id)
            session.commit()
            assert created.id is not None and created.balance == 0
            created_again = get_or_create_premium_account(session, 2, account.id)
            assert created_again.id == created.id  # second call must return the same row, not create a new one

            rows = premium_accounts_for_enterprise(session, 1)
            assert len(rows) == 1 and rows[0]["balance"] == 100.0 and set(rows[0]["insurers"]) == {"测试保司", "第二保司"}

            from backend.models import User
            admin = session.scalar(select(User).where(User.username == "admin"))

            new_account = add_insurer_account(InsurerAccountIn(label="新账户", bank_name="工商银行", account_no="9999", account_holder="测试收款方2"), admin, session)
            assert new_account["status"] == "active"

            updated_account = update_insurer_account(new_account["id"], InsurerAccountUpdate(status="paused"), admin, session)
            assert updated_account["status"] == "paused"

            all_accounts = insurer_accounts(session)
            assert any(a["id"] == new_account["id"] for a in all_accounts)

            new_link = add_insurer_account_link(InsurerAccountLinkIn(insurer="第三保司", account_id=new_account["id"]), admin, session)
            assert new_link["insurer"] == "第三保司"

            try:
                add_insurer_account_link(InsurerAccountLinkIn(insurer="第三保司", account_id=account.id), admin, session)
                raise AssertionError("duplicate insurer link should be rejected")
            except HTTPException as error:
                assert error.status_code == 409

            all_links = insurer_account_links(session)
            assert any(link["id"] == new_link["id"] for link in all_links)

            deleted = delete_insurer_account_link(new_link["id"], admin, session)
            assert deleted["ok"] is True

            # id=1 is the demo enterprise seed_default_accounts() always creates on a fresh DB.
            enterprise = session.get(Enterprise, 1)
            enterprise_id = enterprise.id
            balance_before = get_or_create_premium_account(session, enterprise_id, account.id).balance

            fake_receipt = UploadFile(file=io.BytesIO(b"fake-image-bytes"), filename="receipt.png")
            submitted = asyncio.run(create_recharge_request(
                enterprise_id=enterprise_id, account_type="premium", insurer="测试保司", amount=30.0,
                file=fake_receipt, user=admin, session=session,
            ))
            assert submitted["status"] == "pending" and submitted["account_id"] == account.id

            listed = list_recharge_requests("", admin, session)
            assert any(r["id"] == submitted["id"] for r in listed)

            confirmed = confirm_recharge_request(submitted["id"], admin, session)
            assert confirmed["status"] == "confirmed"
            balance_after = get_or_create_premium_account(session, enterprise_id, account.id).balance
            assert balance_after - balance_before == 30.0

            premium_rows = enterprise_premium_accounts(enterprise_id, admin, session)
            assert any(row["account_id"] == account.id and row["balance"] == balance_after for row in premium_rows)

            try:
                confirm_recharge_request(submitted["id"], admin, session)
                raise AssertionError("confirming an already-confirmed request should fail")
            except HTTPException as error:
                assert error.status_code == 400

            fake_receipt_2 = UploadFile(file=io.BytesIO(b"fake-image-bytes-2"), filename="receipt2.png")
            second_submission = asyncio.run(create_recharge_request(
                enterprise_id=enterprise_id, account_type="usage", insurer="", amount=15.0,
                file=fake_receipt_2, user=admin, session=session,
            ))
            usage_before = enterprise.usage_balance
            rejected = reject_recharge_request(second_submission["id"], "回单金额与申请金额不符", admin, session)
            assert rejected["status"] == "rejected" and rejected["reject_reason"] == "回单金额与申请金额不符"
            assert enterprise.usage_balance == usage_before  # rejecting must not touch the balance

            # --- Task 9 finding: the tests above never exercised the actual
            # multi-insurer-per-account / cross-enterprise aggregation logic in
            # dashboard(). "测试账户" (account) is already shared by "测试保司"
            # (link) and "第二保司" (second_link). Build one active policy under
            # each insurer for enterprise 1 and prove dashboard() sums both
            # insurers' daily premium into that ONE shared account's alert —
            # not just one of them, and not doubled.
            plan_a = add_plan(PlanIn(insurer="测试保司", name="共享账户方案A", price=200, effective_mode="immediate"), admin, session)
            plan_b = add_plan(PlanIn(insurer="第二保司", name="共享账户方案B", price=150, effective_mode="immediate"), admin, session)
            assert plan_a["billing_mode"] == "daily" and plan_b["billing_mode"] == "daily"

            shared_employer = add_actual_employer(ActualEmployerIn(name="共享账户测试工作单位", enterprise_id=enterprise_id), admin, session)
            position_a = add_position(PositionIn(enterprise_id=enterprise_id, actual_employer_id=shared_employer["id"], actual_employer=shared_employer["name"], name="共享账户岗位A", occupation_class="1-3类", plan_id=plan_a["id"]), admin, session)
            position_b = add_position(PositionIn(enterprise_id=enterprise_id, actual_employer_id=shared_employer["id"], actual_employer=shared_employer["name"], name="共享账户岗位B", occupation_class="1-3类", plan_id=plan_b["id"]), admin, session)
            session.get(WorkPosition, position_a["id"]).status = "approved"
            session.get(WorkPosition, position_b["id"]).status = "approved"
            session.commit()

            person_a = add_person(PersonIn(enterprise_id=enterprise_id, name="共享账户员工A", id_number="340123199001019993", position_id=position_a["id"]), admin, session)
            person_b = add_person(PersonIn(enterprise_id=enterprise_id, name="共享账户员工B", id_number="340123199001019985", position_id=position_b["id"]), admin, session)
            insured_status(person_a["id"], "active", admin, session)
            insured_status(person_b["id"], "active", admin, session)

            policy_a = session.get(InsuredPerson, person_a["id"]).policy_id
            policy_b = session.get(InsuredPerson, person_b["id"]).policy_id
            assert policy_a and policy_b and policy_a != policy_b  # two distinct policies, one per insurer, both funding the same shared account

            # Force an alert regardless of the real balance/burn ratio so the alert's
            # daily_burn (the only place dashboard() exposes the computed
            # daily_premium) is observable and assertable below.
            enterprise.alert_days = 999999
            session.commit()

            # Cross-enterprise aggregation: enterprise 2's row on this SAME shared
            # account (created earlier via get_or_create_premium_account(session, 2,
            # account.id) to exercise the "create" branch) must fold into the same
            # admin-view entry as enterprise 1's, not appear as a duplicate row.
            created.balance = 45.0
            session.commit()

            dashboard_data = dashboard_endpoint(admin, session)
            assert "premium_accounts" in dashboard_data
            assert "premium_balance" not in dashboard_data  # replaced, not just supplemented

            matching_rows = [row for row in dashboard_data["premium_accounts"] if row["account_id"] == account.id]
            assert len(matching_rows) == 1  # two enterprises on the same account_id must be ONE aggregated entry, not two
            expected_total_balance = balance_after + 45.0
            assert matching_rows[0]["balance"] == expected_total_balance

            alert = next(a for a in dashboard_data["balance_alerts"] if a["enterprise_id"] == enterprise_id and a["account"] == "premium" and a["account_id"] == account.id)
            # 200/day (测试保司) + 150/day (第二保司), both billing_mode="daily" ->
            # must sum to exactly 350, proving both insurers' active policies are
            # attributed to the one shared account with no double-counting.
            assert alert["daily_burn"] == 350.0

    print("recharge smoke: ok")


if __name__ == "__main__":
    run()
