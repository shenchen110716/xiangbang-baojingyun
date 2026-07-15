"""Smoke test for the salesperson portal login feature.

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
    with tempfile.TemporaryDirectory(prefix="xbb-salesperson-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import User
        from backend.routers.auth import login
        from backend.schemas import LoginIn

        startup()
        with SessionLocal() as session:
            salesperson = User(username="test_salesperson", password_hash=pwd.hash("sp12345"), name="测试业务员", role="salesperson")
            session.add(salesperson); session.commit(); session.refresh(salesperson)

            enterprise_admin = session.scalar(select(User).where(User.username == "enterprise"))
            assert enterprise_admin is not None

            # salesperson logging in with portal="salesperson" succeeds
            token = login(LoginIn(username="test_salesperson", password="sp12345", portal="salesperson"), session)
            assert token.access_token

            # salesperson logging in with portal="admin" or "enterprise" is rejected
            try:
                login(LoginIn(username="test_salesperson", password="sp12345", portal="admin"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            try:
                login(LoginIn(username="test_salesperson", password="sp12345", portal="enterprise"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

            # a non-salesperson account logging in with portal="salesperson" is rejected
            try:
                login(LoginIn(username="enterprise", password="enterprise123", portal="salesperson"), session)
                assert False, "expected 403"
            except HTTPException as e:
                assert e.status_code == 403

    print("salesperson portal smoke: ok")


if __name__ == "__main__":
    run()
