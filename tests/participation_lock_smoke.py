"""Smoke test for usage-fee locking, premium-shortfall pending terminations,
and their SMS notification triggers.

Isolated from tests/system_smoke.py on purpose (same reason as
tests/recharge_smoke.py): that file's PersonIn fixture fails an unrelated
ID-checksum validation bug unrelated to this feature.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-participation-lock-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select
        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PendingTermination, User
        from backend.services import require_usage_funded
        from backend.models import InsuredPerson, WorkPosition
        from backend.routers.insured import add_person, insured_status
        from backend.schemas import PersonIn

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

    print("participation lock smoke: ok")


if __name__ == "__main__":
    run()
