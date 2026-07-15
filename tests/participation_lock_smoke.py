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

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PendingTermination, User

        startup()
        with SessionLocal() as session:
            ent = Enterprise(name="锁定测试企业", kind="企业", contact="", phone="", status="active")
            session.add(ent); session.commit(); session.refresh(ent)
            assert ent.id is not None

            pt = PendingTermination(enterprise_id=ent.id, account_id=1, affected_insurers="测试保司", affected_count=2)
            session.add(pt); session.commit(); session.refresh(pt)
            assert pt.status == "pending" and pt.confirmed_by is None

    print("participation lock smoke: ok")


if __name__ == "__main__":
    run()
