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

            # Step C: premium 继续被拒绝（既有行为不回归）
            from backend.routers.payments import create_payment, get_payment, list_payments, wechat_notify
            from backend.schemas import PaymentIn

            try:
                create_payment(PaymentIn(enterprise_id=enterprise.id, account="premium", amount=10.0), enterprise_user, session)
                raise AssertionError("premium 使用微信支付应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400

            # Step D: native 下单成功
            native_result = create_payment(PaymentIn(enterprise_id=enterprise.id, account="usage", amount=88.0, channel="native"), enterprise_user, session)
            assert native_result["status"] == "pending" and native_result["code_url"]
            native_order_no = native_result["order_no"]

            # Step E: jsapi 下单成功（openid 已在 Step B 绑定）
            jsapi_result = create_payment(PaymentIn(enterprise_id=enterprise.id, account="usage", amount=66.0, channel="jsapi"), enterprise_user, session)
            assert jsapi_result["status"] == "pending" and jsapi_result["prepay_id"]

            # jsapi 未绑定 openid 时必须被拒绝
            try:
                create_payment(PaymentIn(enterprise_id=other_enterprise.id, account="usage", amount=1.0, channel="jsapi"), other_user, session)
                raise AssertionError("未绑定 openid 时 jsapi 下单应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400

            # Step F: wechat-notify 验签失败 —— 不落库、不加余额
            import asyncio
            import hashlib
            import hmac
            import json

            from backend.providers import WeChatPayProvider

            class _FakeRequest:
                def __init__(self, headers, body):
                    self.headers = headers
                    self._body = body

                async def body(self):
                    return self._body

            usage_balance_before = enterprise.usage_balance
            bad_body = json.dumps({"out_trade_no": native_order_no, "status": "paid", "transaction_id": "wx-txn-1"}).encode()
            bad_request = _FakeRequest({"X-Mock-Signature": "not-a-real-signature"}, bad_body)
            try:
                asyncio.run(wechat_notify(bad_request, session))
                raise AssertionError("验签失败的回调应被拒绝")
            except HTTPException as error:
                assert error.status_code == 400
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before
            unpaid = session.scalar(select(PaymentRecord).where(PaymentRecord.order_no == native_order_no))
            assert unpaid.status == "pending"

            # Step G: 正确签名的回调 —— 入账、写账本、写 provider_trade_no/paid_at；重复回调幂等
            good_body = json.dumps({"out_trade_no": native_order_no, "status": "paid", "transaction_id": "wx-txn-1"}).encode()
            signature = hmac.new(WeChatPayProvider.MOCK_NOTIFY_SECRET.encode(), good_body, hashlib.sha256).hexdigest()
            first_notify = asyncio.run(wechat_notify(_FakeRequest({"X-Mock-Signature": signature}, good_body), session))
            assert first_notify["status"] == "paid" and first_notify["idempotent"] is False
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before + 88.0
            paid_row = session.scalar(select(PaymentRecord).where(PaymentRecord.order_no == native_order_no))
            assert paid_row.provider_trade_no == "wx-txn-1" and paid_row.paid_at is not None

            from backend.models import LedgerEntry
            ledger_row = session.scalar(select(LedgerEntry).where(LedgerEntry.business_id == native_order_no))
            assert ledger_row is not None and ledger_row.direction == "credit" and ledger_row.amount == 88.0

            second_notify = asyncio.run(wechat_notify(_FakeRequest({"X-Mock-Signature": signature}, good_body), session))
            assert second_notify["idempotent"] is True
            session.refresh(enterprise)
            assert enterprise.usage_balance == usage_balance_before + 88.0
            ledger_count = len(session.scalars(select(LedgerEntry).where(LedgerEntry.business_id == native_order_no)).all())
            assert ledger_count == 1

            # Step H: 查询接口 —— 企业只能查自己单位的订单，管理员不限
            status_view = get_payment(native_order_no, enterprise_user, session)
            assert status_view["status"] == "paid"
            try:
                get_payment(native_order_no, other_user, session)
                raise AssertionError("无关企业不应能查看该订单")
            except HTTPException as error:
                assert error.status_code == 403

            admin_list = list_payments(None, "", "", session)
            assert any(row["order_no"] == native_order_no for row in admin_list)

        print("wechat pay smoke: ok")


if __name__ == "__main__":
    run()
