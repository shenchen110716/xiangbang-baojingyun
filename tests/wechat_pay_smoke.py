"""Smoke test for the WeChat merchant-account payment channel for 平台服务费
(platform usage-fee) collection: online order creation (native + jsapi),
signed-notify callback (idempotent, rejects bad signatures), ledger/balance
credit, RBAC, and the admin-configurable default-collection-method setting.

Isolated from tests/system_smoke.py for the same reason recharge_smoke.py is
(unrelated PersonIn ID-checksum fixture bug) — builds its own minimal
fixtures. Grows task-by-task; run the whole file after every task.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-wechat-pay-smoke-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["INTEGRATION_MODE"] = "mock"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, PaymentRecord, User

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.username == "admin"))
            enterprise_user = session.scalar(select(User).where(User.username == "enterprise"))
            enterprise = session.scalar(select(Enterprise).where(Enterprise.id == enterprise_user.enterprise_id))
            assert enterprise is not None

            # Step 1: schema roundtrip for the new columns (proves both the
            # SQLAlchemy models AND the SQLite bridge migration are wired).
            probe = PaymentRecord(order_no="PAY-SCHEMA-PROBE", enterprise_id=enterprise.id, account="usage",
                                   amount=1.0, status="pending", provider="wechat", channel="native",
                                   openid=None, provider_trade_no=None, paid_at=None)
            session.add(probe); session.commit(); session.refresh(probe)
            assert probe.channel == "native" and probe.provider_trade_no is None and probe.paid_at is None
            enterprise_user.wx_openid = "probe-openid"
            session.commit()
            reloaded_user = session.scalar(select(User).where(User.id == enterprise_user.id))
            assert reloaded_user.wx_openid == "probe-openid"
            session.delete(probe); session.commit()

            # Step B: openid binding — first bind succeeds, a second account
            # trying to bind the SAME openid must be rejected (prevents one
            # openid paying on behalf of two enterprises).
            from backend.routers.wechat import bind_openid
            from backend.schemas import WeChatBindOpenidIn

            bound = bind_openid(WeChatBindOpenidIn(code="wx-code-123"), enterprise_user, session)
            assert bound["wx_openid"] == "mock-openid-wx-code-123"
            session.refresh(enterprise_user)
            assert enterprise_user.wx_openid == "mock-openid-wx-code-123"

            other_enterprise = Enterprise(name="其他单位", kind="企业", contact="", phone="", status="active")
            session.add(other_enterprise); session.commit(); session.refresh(other_enterprise)
            other_user = User(username="other-enterprise", password_hash=enterprise_user.password_hash, name="其他单位",
                               role="enterprise", enterprise_id=other_enterprise.id, is_owner=True)
            session.add(other_user); session.commit()
            from fastapi import HTTPException
            try:
                bind_openid(WeChatBindOpenidIn(code="wx-code-123"), other_user, session)
                raise AssertionError("同一个 openid 不应能绑定到第二个账号")
            except HTTPException as error:
                assert error.status_code == 409

        print("wechat pay smoke: ok")


if __name__ == "__main__":
    run()
