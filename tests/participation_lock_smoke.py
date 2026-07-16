"""Smoke test for usage-fee locking, premium-shortfall pending terminations,
and their SMS notification triggers.

Isolated from tests/system_smoke.py on purpose (same reason as
tests/recharge_smoke.py): that file's PersonIn fixture fails an unrelated
ID-checksum validation bug unrelated to this feature.
"""
import os
import sys
import tempfile
import threading
from datetime import timedelta
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
        from backend.core.business_time import business_now
        from backend.core.db import SessionLocal
        from backend.models import (
            AuditLog,
            Enterprise,
            EnterprisePremiumAccount,
            InsurancePlan,
            InsuredPerson,
            InsurerAccount,
            InsurerAccountLink,
            PendingTermination,
            Policy,
            PolicyMember,
            RechargeRequest,
            User,
            WorkPosition,
        )
        from backend.services import require_usage_funded
        from backend.routers.insured import add_person, insured_status
        from backend.routers.pending_terminations import confirm_pending_termination, pending_terminations as list_pending_terminations
        from backend.routers.recharge_requests import confirm_recharge_request, reject_recharge_request
        from backend.routers.dashboard import dashboard as dashboard_endpoint
        from backend.schemas import PersonIn
        from backend.providers import ProviderResult
        from backend.services import notify_enterprise, scan_premium_shortfalls

        startup()
        with SessionLocal() as session:
            def add_covered_person(enterprise, insurer, name):
                """Create a real current policy link so account scoping is testable."""
                plan = InsurancePlan(
                    insurer=insurer,
                    name=f"{insurer}测试方案",
                    effective_mode="next_day",
                    status="active",
                )
                session.add(plan); session.flush()
                position = WorkPosition(
                    enterprise_id=enterprise.id,
                    name=f"{name}岗位",
                    plan_id=plan.id,
                    status="approved",
                )
                session.add(position); session.flush()
                policy = Policy(
                    policy_no=f"LOCK-{session.query(Policy).count() + 1}",
                    enterprise_id=enterprise.id,
                    plan_id=plan.id,
                    status="active",
                )
                session.add(policy); session.flush()
                person = InsuredPerson(
                    enterprise_id=enterprise.id,
                    name=name,
                    position_id=position.id,
                    policy_id=policy.id,
                    status="active",
                )
                session.add(person); session.flush()
                session.add(PolicyMember(
                    policy_id=policy.id,
                    person_id=person.id,
                    effective_at=business_now() - timedelta(days=2),
                    status="active",
                ))
                session.commit(); session.refresh(person)
                return person

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
                name="无法归属保司的历史人员",
                status="active",
            )
            session.add(positionless_active_person); session.commit()
            covered_scan_person = add_covered_person(scan_ent, "扫描测试保司", "扫描账户员工")
            shortfall = EnterprisePremiumAccount(enterprise_id=scan_ent.id, account_id=scan_account.id, balance=-10.0)
            session.add(shortfall); session.commit()

            created_1 = scan_premium_shortfalls(session, enterprise_id=scan_ent.id)
            assert len(created_1) == 1, created_1
            assert created_1[0].affected_insurers == "扫描测试保司"
            assert created_1[0].affected_count == 1
            assert covered_scan_person.status == "active"
            assert positionless_active_person.status == "active", "unattributed people must never be auto-stopped"
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
            add_covered_person(scan_ent, "并发扫描保司", "并发扫描员工")
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

            # Confirmation is scoped to the shortfall account's insurers. It
            # must not stop another funded account or an unattributed legacy
            # row in the same enterprise. The covered target uses a next-day
            # plan, proving involuntary shortfall termination bypasses the
            # voluntary next-day timing check.
            confirm_account = InsurerAccount(label="确认停保账户", bank_name="", account_no="", account_holder="", status="active")
            safe_account = InsurerAccount(label="正常余额账户", bank_name="", account_no="", account_holder="", status="active")
            session.add_all([confirm_account, safe_account]); session.commit(); session.refresh(confirm_account); session.refresh(safe_account)
            session.add(InsurerAccountLink(insurer="确认停保保司", account_id=confirm_account.id)); session.commit()
            session.add(InsurerAccountLink(insurer="正常余额保司", account_id=safe_account.id)); session.commit()
            confirm_ent = Enterprise(name="确认停保企业", kind="企业", contact="", phone="", status="active")
            session.add(confirm_ent); session.commit(); session.refresh(confirm_ent)
            target_person = add_covered_person(confirm_ent, "确认停保保司", "欠费账户员工")
            safe_person = add_covered_person(confirm_ent, "正常余额保司", "正常账户员工")
            temporary_person = add_covered_person(confirm_ent, "确认停保保司", "仍在保障期临时员工")
            temporary_member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == temporary_person.id))
            temporary_member.terminated_at = business_now() + timedelta(hours=1)
            temporary_member.status = "terminated"
            temporary_person.policy_id = None
            temporary_person.status = "stopped"
            target_policy = session.get(Policy, target_person.policy_id)
            moved_position = WorkPosition(
                enterprise_id=confirm_ent.id,
                name="岗位已改到欠费产品",
                plan_id=target_policy.plan_id,
                status="approved",
            )
            session.add(moved_position); session.flush()
            safe_person.position_id = moved_position.id
            session.add(PolicyMember(
                policy_id=target_policy.id,
                person_id=safe_person.id,
                status="active",
                effective_at=business_now() + timedelta(days=1),
            ))
            session.flush()
            legacy_person = InsuredPerson(enterprise_id=confirm_ent.id, name="无法归属历史员工", status="active")
            session.add(legacy_person)
            session.add_all([
                EnterprisePremiumAccount(enterprise_id=confirm_ent.id, account_id=confirm_account.id, balance=-20.0),
                EnterprisePremiumAccount(enterprise_id=confirm_ent.id, account_id=safe_account.id, balance=100.0),
            ]); session.commit()
            fresh_pending = scan_premium_shortfalls(session, enterprise_id=confirm_ent.id)
            assert len(fresh_pending) == 1
            listed = next(row for row in list_pending_terminations(session) if row["id"] == fresh_pending[0].id)
            assert listed["enterprise_name"] == confirm_ent.name
            assert listed["account_label"] == confirm_account.label
            assert listed["current_affected_count"] == 2
            assert listed["affected_people"] == [
                {"id": target_person.id, "name": target_person.name},
                {"id": temporary_person.id, "name": temporary_person.name},
            ]

            result = confirm_pending_termination(fresh_pending[0].id, admin_user, session)
            assert result["status"] == "confirmed" and result["terminated_count"] == 2
            session.refresh(target_person); session.refresh(safe_person); session.refresh(temporary_person); session.refresh(legacy_person)
            assert target_person.status == "stopped"
            assert safe_person.status == "active", "a funded live policy remains authoritative even if the current position changed"
            assert temporary_person.status == "stopped"
            assert legacy_person.status == "active", "an unattributed row must not be affected"
            terminated_member = session.scalar(select(PolicyMember).where(PolicyMember.person_id == target_person.id))
            assert terminated_member.status == "terminated" and terminated_member.terminated_at is not None
            session.refresh(temporary_member)
            assert temporary_member.terminated_at <= business_now(), "forced termination must close a still-live future-ended period now"
            try:
                confirm_pending_termination(fresh_pending[0].id, admin_user, session)
                assert False, "expected 400 for re-confirming an already processed task"
            except HTTPException as error:
                assert error.status_code == 400

            # SQLite ignores SELECT ... FOR UPDATE. Two independent requests
            # must still have one atomic winner, one clean 400 loser, one
            # audit, and one confirmation notification.
            concurrent_account = InsurerAccount(label="并发确认账户", status="active")
            concurrent_ent = Enterprise(name="并发确认企业", kind="企业", status="active")
            session.add_all([concurrent_account, concurrent_ent]); session.commit()
            session.add(InsurerAccountLink(insurer="并发确认保司", account_id=concurrent_account.id)); session.commit()
            add_covered_person(concurrent_ent, "并发确认保司", "并发确认员工")
            session.add(EnterprisePremiumAccount(
                enterprise_id=concurrent_ent.id,
                account_id=concurrent_account.id,
                balance=-1.0,
            )); session.commit()
            concurrent_pending = scan_premium_shortfalls(session, concurrent_ent.id)[0]
            barrier = threading.Barrier(2)
            concurrent_results = []
            concurrent_sms = []
            result_lock = threading.Lock()

            def run_concurrent_confirm():
                with SessionLocal() as concurrent_session:
                    concurrent_admin = concurrent_session.scalar(select(User).where(User.username == "admin"))
                    barrier.wait()
                    try:
                        outcome = confirm_pending_termination(concurrent_pending.id, concurrent_admin, concurrent_session)
                    except HTTPException as error:
                        outcome = error.status_code
                    except Exception as error:
                        outcome = type(error).__name__
                    with result_lock:
                        concurrent_results.append(outcome)

            with patch(
                "backend.routers.pending_terminations.notify_enterprise",
                side_effect=lambda *args: concurrent_sms.append(args),
            ):
                workers = [threading.Thread(target=run_concurrent_confirm) for _ in range(2)]
                for worker in workers: worker.start()
                for worker in workers: worker.join(timeout=10)
            assert all(not worker.is_alive() for worker in workers), "concurrent confirmation must not deadlock"
            assert sorted(400 if result == 400 else 200 if isinstance(result, dict) else str(result) for result in concurrent_results) == [200, 400], concurrent_results
            assert len(concurrent_sms) == 1
            assert session.query(AuditLog).filter(
                AuditLog.action == "confirm",
                AuditLog.object_type == "pending_termination",
                AuditLog.object_id == str(concurrent_pending.id),
            ).count() == 1

            # Confirmation always rechecks the locked balance row. A recharge
            # after task creation dismisses the task and must not stop anyone.
            recovered_account = InsurerAccount(label="确认前充值账户", bank_name="", account_no="", account_holder="", status="active")
            session.add(recovered_account); session.commit(); session.refresh(recovered_account)
            session.add(InsurerAccountLink(insurer="确认前充值保司", account_id=recovered_account.id)); session.commit()
            recovered_person = add_covered_person(confirm_ent, "确认前充值保司", "确认前充值员工")
            recovered_balance = EnterprisePremiumAccount(enterprise_id=confirm_ent.id, account_id=recovered_account.id, balance=-1.0)
            session.add(recovered_balance); session.commit()
            recovered_pending = scan_premium_shortfalls(session, confirm_ent.id)[0]
            recovered_balance.balance = 50.0
            session.commit()
            try:
                confirm_pending_termination(recovered_pending.id, admin_user, session)
                assert False, "a recovered account must not be terminated"
            except HTTPException as error:
                assert error.status_code == 409 and "已自动撤销" in error.detail
            session.refresh(recovered_pending); session.refresh(recovered_person)
            assert recovered_pending.status == "dismissed"
            assert recovered_person.status == "active"

            # Loading the pending-termination page is itself a lazy-scan
            # trigger; an administrator must not have to visit the dashboard
            # first.
            direct_list_ent = Enterprise(name="列表扫描企业", kind="企业", contact="", phone="", status="active")
            direct_list_account = InsurerAccount(label="列表扫描账户", bank_name="", account_no="", account_holder="", status="active")
            session.add_all([direct_list_ent, direct_list_account]); session.commit(); session.refresh(direct_list_ent); session.refresh(direct_list_account)
            session.add(InsurerAccountLink(insurer="列表扫描保司", account_id=direct_list_account.id)); session.commit()
            direct_list_person = add_covered_person(direct_list_ent, "列表扫描保司", "列表扫描员工")
            session.add(EnterprisePremiumAccount(enterprise_id=direct_list_ent.id, account_id=direct_list_account.id, balance=-1.0)); session.commit()
            assert session.scalar(select(PendingTermination).where(
                PendingTermination.enterprise_id == direct_list_ent.id,
                PendingTermination.account_id == direct_list_account.id,
            )) is None
            direct_rows = list_pending_terminations(session)
            direct_row = next(row for row in direct_rows if row["enterprise_id"] == direct_list_ent.id)
            assert direct_row["affected_people"] == [{"id": direct_list_person.id, "name": direct_list_person.name}]

            # All four business trigger families call notify_enterprise with
            # stable templates and params. Usage locking is deduplicated once
            # per business day; scanning notifies only newly-created tasks.
            trigger_calls = []

            def record_trigger(trigger_session, enterprise_id, template_name, template_params):
                trigger_calls.append((enterprise_id, template_name, template_params))

            lock_notify_ent = Enterprise(name="锁定短信企业", kind="企业", contact="", phone="", status="active", usage_balance=0.0)
            session.add(lock_notify_ent); session.commit(); session.refresh(lock_notify_ent)
            with patch("backend.services.participation_lock.notify_enterprise", side_effect=record_trigger):
                for _ in range(2):
                    try:
                        require_usage_funded(session, lock_notify_ent, admin_user)
                    except HTTPException as error:
                        assert error.status_code == 403
            assert [call[1] for call in trigger_calls].count("usage_locked") == 1, trigger_calls

            warning_ent = Enterprise(name="保费预警短信企业", kind="企业", contact="", phone="", status="active")
            warning_account = InsurerAccount(label="保费预警短信账户", bank_name="", account_no="", account_holder="", status="active")
            session.add_all([warning_ent, warning_account]); session.commit(); session.refresh(warning_ent); session.refresh(warning_account)
            add_covered_person(warning_ent, "保费预警短信保司", "保费预警在保员工")
            session.add(InsurerAccountLink(insurer="保费预警短信保司", account_id=warning_account.id))
            session.add(EnterprisePremiumAccount(enterprise_id=warning_ent.id, account_id=warning_account.id, balance=-1.0)); session.commit()
            with patch("backend.services.termination_scan.notify_enterprise", side_effect=record_trigger):
                warning_created = scan_premium_shortfalls(session, warning_ent.id)
                assert len(warning_created) == 1
                assert scan_premium_shortfalls(session, warning_ent.id) == []
            assert [call[1] for call in trigger_calls].count("premium_shortfall_warning") == 1

            confirm_sms_ent = Enterprise(name="停保确认短信企业", kind="企业", contact="", phone="", status="active")
            confirm_sms_account = InsurerAccount(label="停保确认短信账户", bank_name="", account_no="", account_holder="", status="active")
            session.add_all([confirm_sms_ent, confirm_sms_account]); session.commit(); session.refresh(confirm_sms_ent); session.refresh(confirm_sms_account)
            add_covered_person(confirm_sms_ent, "停保确认短信保司", "停保确认在保员工")
            session.add(InsurerAccountLink(insurer="停保确认短信保司", account_id=confirm_sms_account.id))
            session.add(EnterprisePremiumAccount(enterprise_id=confirm_sms_ent.id, account_id=confirm_sms_account.id, balance=-1.0)); session.commit()
            confirm_sms_pending = scan_premium_shortfalls(session, confirm_sms_ent.id)[0]
            with patch("backend.routers.pending_terminations.notify_enterprise", side_effect=record_trigger):
                confirm_pending_termination(confirm_sms_pending.id, admin_user, session)
            assert [call[1] for call in trigger_calls].count("termination_confirmed") == 1

            recharge_ent = Enterprise(name="充值短信企业", kind="企业", contact="", phone="", status="active")
            session.add(recharge_ent); session.commit(); session.refresh(recharge_ent)
            confirm_request = RechargeRequest(enterprise_id=recharge_ent.id, account_type="usage", amount=10.0, status="pending", created_by=admin_user.id)
            reject_request = RechargeRequest(enterprise_id=recharge_ent.id, account_type="usage", amount=20.0, status="pending", created_by=admin_user.id)
            session.add_all([confirm_request, reject_request]); session.commit(); session.refresh(confirm_request); session.refresh(reject_request)
            with patch("backend.routers.recharge_requests.notify_enterprise", side_effect=record_trigger):
                confirm_recharge_request(confirm_request.id, admin_user, session)
                reject_recharge_request(reject_request.id, "凭证不清晰", admin_user, session)
            assert [call[1] for call in trigger_calls].count("recharge_confirmed") == 1
            assert [call[1] for call in trigger_calls].count("recharge_rejected") == 1

            dashboard_ent = Enterprise(name="看板惰性扫描企业", kind="企业", contact="", phone="", status="active")
            dashboard_account = InsurerAccount(label="看板惰性扫描账户", bank_name="", account_no="", account_holder="", status="active")
            session.add_all([dashboard_ent, dashboard_account]); session.commit(); session.refresh(dashboard_ent); session.refresh(dashboard_account)
            add_covered_person(dashboard_ent, "看板惰性扫描保司", "看板扫描在保员工")
            session.add(InsurerAccountLink(insurer="看板惰性扫描保司", account_id=dashboard_account.id))
            session.add(EnterprisePremiumAccount(enterprise_id=dashboard_ent.id, account_id=dashboard_account.id, balance=-1.0)); session.commit()
            dashboard_result = dashboard_endpoint(admin_user, session)
            dashboard_pending = session.scalar(select(PendingTermination).where(
                PendingTermination.enterprise_id == dashboard_ent.id,
                PendingTermination.account_id == dashboard_account.id,
                PendingTermination.status == "pending",
            ))
            assert dashboard_pending is not None
            current_pending = session.query(PendingTermination).filter(PendingTermination.status == "pending").count()
            assert dashboard_result["pending_terminations_count"] == current_pending

            enterprise_user = session.scalar(select(User).where(User.username == "enterprise"))
            enterprise_result = dashboard_endpoint(enterprise_user, session)
            assert enterprise_result["pending_terminations_count"] == 0

    print("participation lock smoke: ok")


if __name__ == "__main__":
    run()
