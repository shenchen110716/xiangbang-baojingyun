"""Smoke test for usage-fee locking, premium-shortfall pending terminations,
and their SMS notification triggers.

Isolated from tests/system_smoke.py on purpose (same reason as
tests/recharge_smoke.py): that file's PersonIn fixture fails an unrelated
ID-checksum validation bug unrelated to this feature.
"""
import os
import sys
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-participation-lock-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select
        from sqlalchemy.exc import IntegrityError
        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PendingTermination, User
        from backend.services import require_usage_funded
        from backend.models import InsuredPerson, WorkPosition
        from backend.routers.insured import add_person, insured_status
        from backend.routers.pending_terminations import confirm_pending_termination, pending_terminations as list_pending_terminations
        from backend.schemas import PersonIn
        from backend.models import EnterprisePremiumAccount, InsurerAccount, InsurerAccountLink
        from backend.models import AuditLog
        from backend.providers import ProviderResult
        from backend.services import notify_enterprise, scan_premium_shortfalls

        startup()
        with SessionLocal() as session:
            ent = Enterprise(name="锁定测试企业", kind="企业", contact="", phone="", status="active")
            session.add(ent); session.commit(); session.refresh(ent)
            assert ent.id is not None

            pt = PendingTermination(enterprise_id=ent.id, account_id=1, affected_insurers="测试保司", affected_count=2)
            session.add(pt); session.commit(); session.refresh(pt)
            assert pt.status == "pending" and pt.confirmed_by is None

            # require_usage_funded: real-time check, no caching
            admin_user = session.scalar(select(User).where(User.username == "admin"))
            funded_ent = Enterprise(name="有余额企业", kind="企业", contact="", phone="", status="active", usage_balance=50.0)
            session.add(funded_ent); session.commit(); session.refresh(funded_ent)
            require_usage_funded(session, funded_ent, admin_user)  # must not raise

            unfunded_ent = Enterprise(name="无余额企业", kind="企业", contact="", phone="", status="active", usage_balance=0.0)
            session.add(unfunded_ent); session.commit(); session.refresh(unfunded_ent)
            try:
                require_usage_funded(session, unfunded_ent, admin_user)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            negative_ent = Enterprise(name="负余额企业", kind="企业", contact="", phone="", status="active", usage_balance=-5.0)
            session.add(negative_ent); session.commit(); session.refresh(negative_ent)
            try:
                require_usage_funded(session, negative_ent, admin_user)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            # unlocks immediately on the very next check, no separate unlock step
            unfunded_ent.usage_balance = 10.0
            session.commit()
            require_usage_funded(session, unfunded_ent, admin_user)  # must not raise now

            # add_person is blocked when the target enterprise has no usage balance
            # (admin_user was already fetched in Task 2's test block above, reused here)
            locked_ent = Enterprise(name="锁定集成测试企业", kind="企业", contact="", phone="", status="active", usage_balance=0.0)
            session.add(locked_ent); session.commit(); session.refresh(locked_ent)
            try:
                add_person(PersonIn(enterprise_id=locked_ent.id, name="测试", id_number="110101199003070038"), admin_user, session)
                assert False, "expected 403 for unfunded enterprise"
            except HTTPException as e:
                assert e.status_code == 403 and "使用费余额不足" in e.detail

            # unlocks on the very next call after a recharge, no separate step
            locked_ent.usage_balance = 100.0
            session.commit()
            created = add_person(PersonIn(enterprise_id=locked_ent.id, name="测试", id_number="110101199003070038"), admin_user, session)
            assert created["id"] is not None

            # PATCH .../status is also gated
            locked_ent.usage_balance = 0.0
            session.commit()
            try:
                insured_status(created["id"], status_value="active", user=admin_user, session=session)
                assert False, "expected 403 for unfunded enterprise on status change"
            except HTTPException as e:
                assert e.status_code == 403

            # scan_premium_shortfalls: creates a pending record for a shortfall account
            scan_account = InsurerAccount(label="扫描测试账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(scan_account); session.commit(); session.refresh(scan_account)
            scan_link = InsurerAccountLink(insurer="扫描测试保司", account_id=scan_account.id)
            session.add(scan_link); session.commit()
            scan_ent = Enterprise(name="扫描测试企业", kind="企业", contact="", phone="", status="active")
            session.add(scan_ent); session.commit(); session.refresh(scan_ent)
            positionless_active_person = InsuredPerson(
                enterprise_id=scan_ent.id,
                name="无岗位在保人员",
                status="active",
            )
            session.add(positionless_active_person); session.commit()
            shortfall = EnterprisePremiumAccount(enterprise_id=scan_ent.id, account_id=scan_account.id, balance=-10.0)
            session.add(shortfall); session.commit()

            created_1 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert len(created_1) == 1, created_1
            assert created_1[0].affected_insurers == "扫描测试保司"
            assert created_1[0].affected_count == 1, "active people without a position must be counted"
            assert created_1[0].status == "pending"

            # The database, not only the pre-insert lookup, forbids a second
            # live pending task for the same enterprise/account pair.
            session.add(PendingTermination(
                enterprise_id=scan_ent.id,
                account_id=scan_account.id,
                affected_insurers="扫描测试保司",
                affected_count=1,
            ))
            try:
                session.commit()
                assert False, "the live pending-task invariant must reject duplicates"
            except IntegrityError:
                session.rollback()

            # A competing scanner can create the row after this scanner has
            # checked for it. Inject that committed-in-the-outer-transaction
            # competitor immediately before the scanner opens its savepoint;
            # the losing insert must be contained and reported as no new task.
            race_account = InsurerAccount(label="并发扫描账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(race_account); session.commit(); session.refresh(race_account)
            session.add(InsurerAccountLink(insurer="并发扫描保司", account_id=race_account.id)); session.commit()
            session.add(EnterprisePremiumAccount(enterprise_id=scan_ent.id, account_id=race_account.id, balance=-10.0)); session.commit()
            original_begin_nested = session.begin_nested
            injected_competitor = False

            def begin_nested_after_competitor():
                nonlocal injected_competitor
                if not injected_competitor:
                    session.execute(PendingTermination.__table__.insert().values(
                        enterprise_id=scan_ent.id,
                        account_id=race_account.id,
                        affected_insurers="并发扫描保司",
                        affected_count=1,
                        status="pending",
                    ))
                    injected_competitor = True
                return original_begin_nested()

            session.begin_nested = begin_nested_after_competitor
            try:
                race_created = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            finally:
                session.begin_nested = original_begin_nested
            assert injected_competitor, "scanner must protect its insert with a savepoint"
            assert race_created == [], "a competing live task must not make scanning fail or duplicate"
            race_pending = session.scalar(select(PendingTermination).where(
                PendingTermination.enterprise_id == scan_ent.id,
                PendingTermination.account_id == race_account.id,
                PendingTermination.status == "pending",
            ))
            assert race_pending is not None

            # idempotent: scanning again does not create a duplicate
            created_2 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert created_2 == [], "must not create a duplicate pending record"
            still_one_pending = session.scalar(select(PendingTermination).where(PendingTermination.enterprise_id == scan_ent.id, PendingTermination.status == "pending"))
            assert still_one_pending is not None

            # auto-dismiss: recharging the account clears the pending record without admin action
            shortfall.balance = 50.0
            session.commit()
            created_3 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert created_3 == []
            dismissed = session.scalar(select(PendingTermination).where(PendingTermination.enterprise_id == scan_ent.id))
            assert dismissed.status == "dismissed" and dismissed.dismissed_at is not None

            # notify_enterprise: all active enterprise users with nonblank
            # phones receive the exact template and parameters. A rejected
            # provider result and a thrown provider error are fire-and-forget
            # failures: they create recipient-safe audit records, never leak
            # a phone into the audit detail, and never raise to the caller.
            notify_ent = Enterprise(name="通知测试企业", kind="企业", contact="", phone="", status="active")
            session.add(notify_ent); session.commit(); session.refresh(notify_ent)
            owner = User(username="notify_owner", password_hash="x", name="主管", role="enterprise", enterprise_id=notify_ent.id, is_owner=True, phone="13800000001")
            operator = User(username="notify_operator", password_hash="x", name="操作员", role="enterprise", enterprise_id=notify_ent.id, is_owner=False, phone="13800000002")
            no_phone = User(username="notify_nophone", password_hash="x", name="无手机号", role="enterprise", enterprise_id=notify_ent.id, is_owner=False, phone="   ")
            session.add_all([owner, operator, no_phone]); session.commit()

            class RecordingSmsProvider:
                def __init__(self):
                    self.calls = []

                def send_sms(self, phone, template, params):
                    self.calls.append((phone, template, params))
                    if len(self.calls) == 1:
                        return ProviderResult(False, "sms", "REJECTED", {}, "provider rejected")
                    raise RuntimeError("provider unavailable")

            provider = RecordingSmsProvider()
            template = "recharge_confirmed"
            params = {"amount": 100}
            with patch("backend.services.notify.sms_provider", return_value=provider):
                notify_enterprise(session, notify_ent.id, template, params)  # must not raise

            assert provider.calls == [
                (owner.phone, template, params),
                (operator.phone, template, params),
            ], provider.calls
            failure_audits = session.scalars(select(AuditLog).where(
                AuditLog.action == "sms_failed",
                AuditLog.object_type == "enterprise_notification",
                AuditLog.object_id == str(notify_ent.id),
            )).all()
            assert {entry.user_id for entry in failure_audits} == {owner.id, operator.id}, failure_audits
            assert all(template in entry.detail for entry in failure_audits)
            assert all(f"recipient_user_id={entry.user_id}" in entry.detail for entry in failure_audits)
            assert all(owner.phone not in entry.detail and operator.phone not in entry.detail for entry in failure_audits)
            assert all("provider rejected" not in entry.detail for entry in failure_audits)

            # An audit persistence failure is also fire-and-forget: the
            # notifier must roll the failed transaction back so the shared
            # request Session remains usable for response serialization.
            def failing_audit(audit_session, *args, **kwargs):
                audit_session.add(AuditLog(user_id=None, action="invalid", object_type="test", object_id=""))
                audit_session.commit()

            rejected_provider = RecordingSmsProvider()
            with patch("backend.services.notify.sms_provider", return_value=rejected_provider), patch("backend.services.notify.audit", side_effect=failing_audit):
                notify_enterprise(session, notify_ent.id, "audit_failure", {})
            assert session.scalar(select(User.id).where(User.id == owner.id)) == owner.id

            # Confirming a pending termination stops every active person in
            # the enterprise, including legacy rows without a position.
            confirm_account = InsurerAccount(label="确认停保账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(confirm_account); session.commit(); session.refresh(confirm_account)
            session.add(InsurerAccountLink(insurer="确认停保保司", account_id=confirm_account.id)); session.commit()
            confirm_ent = Enterprise(name="确认停保企业", kind="企业", contact="", phone="", status="active")
            session.add(confirm_ent); session.commit(); session.refresh(confirm_ent)
            session.add_all([
                InsuredPerson(enterprise_id=confirm_ent.id, name="有岗位前状态员工", status="active"),
                InsuredPerson(enterprise_id=confirm_ent.id, name="无岗位在保员工", status="active"),
            ]); session.commit()
            session.add(EnterprisePremiumAccount(enterprise_id=confirm_ent.id, account_id=confirm_account.id, balance=-20.0)); session.commit()
            fresh_pending = scan_premium_shortfalls(session, enterprise_id=confirm_ent.id)
            assert len(fresh_pending) == 1
            assert any(row["id"] == fresh_pending[0].id for row in list_pending_terminations(session))

            result = confirm_pending_termination(fresh_pending[0].id, admin_user, session)
            assert result["status"] == "confirmed" and result["terminated_count"] == 2
            statuses = session.scalars(select(InsuredPerson.status).where(InsuredPerson.enterprise_id == confirm_ent.id)).all()
            assert statuses == ["stopped", "stopped"], statuses
            try:
                confirm_pending_termination(fresh_pending[0].id, admin_user, session)
                assert False, "expected 400 for re-confirming an already processed task"
            except HTTPException as error:
                assert error.status_code == 400

    print("participation lock smoke: ok")


if __name__ == "__main__":
    run()
