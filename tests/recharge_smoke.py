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

from backend.core.migrations import migrate_premium_balances
from backend.services.recharge import (
    resolve_account_for_insurer, insurers_for_account, insurer_account_dict,
    get_or_create_premium_account, premium_accounts_for_enterprise,
)


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-recharge-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import InsurerAccount, InsurerAccountLink, EnterprisePremiumAccount, RechargeRequest, LedgerEntry

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

            rows = premium_accounts_for_enterprise(session, 1)
            assert len(rows) == 1 and rows[0]["balance"] == 100.0 and set(rows[0]["insurers"]) == {"测试保司", "第二保司"}

    print("recharge smoke: ok")


if __name__ == "__main__":
    run()
