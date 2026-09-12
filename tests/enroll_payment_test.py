"""扫码参保：个人缴纳支付流程（H5方案）。

覆盖：
- 创建支付订单：POST /api/enroll/{submission_id}/payment-order
  返回 mweb_url（H5支付链接），order_no 持久化到 submission
- 查询支付状态：GET /api/enroll/{submission_id}/payment-status
- 异步通知回调：POST /api/enroll/payment-callback
  验签 → 更新 payment_status = 'paid'
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enroll-payment-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import (
            ActualEmployer, Enterprise, InsurancePlan, PositionEnrollSubmission,
            User, WorkPosition,
        )
        from backend.providers import wechat_pay_provider
        from backend.routers.enroll import (
            create_payment_order, get_payment_status, wechat_payment_callback,
        )

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            # 基础数据
            enterprise = Enterprise(
                name="支付测试企业", kind="企业", contact="c", phone="p",
                status="active", usage_balance=999999.0
            )
            session.add(enterprise)
            session.commit()
            session.refresh(enterprise)

            employer = ActualEmployer(
                enterprise_id=enterprise.id, name="测试用工单位",
                credit_code="91110108551385082Q", status="active"
            )
            session.add(employer)
            session.commit()
            session.refresh(employer)

            plan = InsurancePlan(
                insurer="测试保司", name="测试产品", price=99.99, insurer_id=1,
                billing_mode="monthly", status="active"
            )
            session.add(plan)
            session.commit()
            session.refresh(plan)

            position = WorkPosition(
                enterprise_id=enterprise.id, actual_employer_id=employer.id,
                actual_employer=employer.name, name="测试岗位",
                plan_id=plan.id, status="approved", enable_personal_pay=True
            )
            session.add(position)
            session.commit()
            session.refresh(position)

            # 创建个人缴纳的 submission（payment_status = 'pending'）
            submission = PositionEnrollSubmission(
                position_id=position.id, payment_mode="personal",
                name="张三", id_number_cipher="encrypted-id", phone="13800000000",
                payment_status="pending", review_status="pending"
            )
            session.add(submission)
            session.commit()
            session.refresh(submission)

            # 1. 创建支付订单
            result = create_payment_order(submission.id, session)
            assert "mweb_url" in result, "mweb_url 应该在返回值中"
            assert "order_no" in result, "order_no 应该在返回值中"
            order_no = result["order_no"]
            assert result["submission_id"] == submission.id

            # 验证 order_no 已保存到 submission
            session.refresh(submission)
            assert submission.order_no == order_no, f"order_no 应该保存为 {order_no}"

            # 2. 查询支付状态（支付前）
            status_before = get_payment_status(submission.id, session)
            assert status_before["payment_status"] == "pending"
            assert status_before["payment_mode"] == "personal"

            # 3. 模拟微信异步通知（支付成功）
            provider = wechat_pay_provider()
            # mock 模式：手动构造 notify 数据
            notify_payload = {
                "out_trade_no": order_no,
                "status": "paid",
                "transaction_id": "mock-txn-id",
            }
            import json
            raw_body = json.dumps(notify_payload).encode()
            headers = {
                "X-Mock-Signature": provider.verify_notify.__self__.__class__.MOCK_NOTIFY_SECRET,
            }
            # 重新计算真实签名
            import hashlib
            import hmac
            headers["X-Mock-Signature"] = (
                hmac.new(provider.MOCK_NOTIFY_SECRET.encode(), raw_body, hashlib.sha256).hexdigest()
            )

            # 调用回调端点（这里用 mock request 对象模拟）
            class MockRequest:
                def __init__(self, h: dict, b: bytes):
                    self.headers = h
                    self._body = b
                async def body(self): return self._body

            import asyncio
            callback_result = asyncio.run(
                wechat_payment_callback(MockRequest(headers, raw_body), session)
            )
            assert callback_result["code"] == "SUCCESS"

            # 4. 查询支付状态（支付后）
            session.refresh(submission)
            status_after = get_payment_status(submission.id, session)
            assert status_after["payment_status"] == "paid", (
                f"支付后应该是 'paid'，但得到 {status_after['payment_status']}"
            )

            # 5. 验证数据库状态
            session.refresh(submission)
            assert submission.payment_status == "paid"
            assert submission.order_no == order_no
            assert submission.review_status == "pending"  # 审核状态不变

        print("enroll payment test: PASS")


if __name__ == "__main__":
    run()
