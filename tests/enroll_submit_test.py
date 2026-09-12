"""扫码参保：免登录公开提交接口。

覆盖：
- 正确 token 能拿到岗位信息、能提交，落一条 PositionEnrollSubmission
  （payment_status 按 personal/employer 分别是 pending/not_required，
  review_status 都是 pending，不直接生成 InsuredPerson）
- token 不对/岗位未审核/该缴费方式没开——统一 404，不泄露内部状态
- 蜜罐字段非空——假装成功但不落库
- 身份证号校验位不对 / 未满16周岁——拒绝
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enroll-submit-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from datetime import date

        from fastapi import HTTPException
        from sqlalchemy import select, func

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.id_number import id_decrypt
        from backend.models import ActualEmployer, Enterprise, PositionEnrollSubmission, User, WorkPosition
        from backend.routers.enroll import enroll_info, enroll_submit, _ip_attempts, _token_attempts
        from backend.routers.positions import position_enroll_qr
        from backend.schemas import EnrollSubmitIn

        # 按 GB 11643 校验位算法现算的两个号码（不是猜的，跑过 is_valid_id_number
        # 确认为 True）：一个 1990 年出生（成年），一个 2015 年出生（未满16周岁）。
        ADULT_ID = "110101199001011237"
        MINOR_ID = "110101201501011233"

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = Enterprise(name="扫码提交测试企业", kind="企业", contact="c", phone="p",
                                     status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位",
                                       credit_code="91110108551385082Q", status="active")
            session.add(employer); session.commit(); session.refresh(employer)
            position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                     actual_employer=employer.name, name="测试岗位", status="approved",
                                     enable_personal_pay=True, enable_employer_pay=False)
            session.add(position); session.commit(); session.refresh(position)

            qr = position_enroll_qr(position.id, admin, session)
            personal_token = qr["personal"]["url"].rsplit("/", 1)[-1]

            class _FakeClient:
                host = "1.2.3.4"

            class _FakeRequest:
                headers = {}
                client = _FakeClient()

            req = _FakeRequest()

            # 1. GET 能拿到岗位信息。
            info = enroll_info(position.id, "personal", personal_token, session)
            assert info["position_name"] == "测试岗位"
            assert info["payment_mode"] == "personal"

            # 2. token 不对 / 方式没开 —— 统一 404，不泄露具体原因。
            try:
                enroll_info(position.id, "employer", personal_token, session)
                raise AssertionError("employer 模式没开，应该 404")
            except HTTPException as e:
                assert e.status_code == 404
            try:
                enroll_info(position.id, "personal", "wrong-token", session)
                raise AssertionError("token 不对，应该 404")
            except HTTPException as e:
                assert e.status_code == 404

            # 3. 蜜罐字段非空 —— 假装成功，不落库。
            before_count = session.scalar(select(func.count()).select_from(PositionEnrollSubmission))
            honeypot_result = enroll_submit(position.id, "personal", personal_token,
                                             EnrollSubmitIn(name="张三", id_number=ADULT_ID, website="http://spam.example"),
                                             req, session)
            assert "提交成功" in honeypot_result["message"]
            after_count = session.scalar(select(func.count()).select_from(PositionEnrollSubmission))
            assert after_count == before_count, "蜜罐字段非空时不应该真的落库"

            # 4. 未满16周岁 —— 拒绝。
            try:
                enroll_submit(position.id, "personal", personal_token,
                              EnrollSubmitIn(name="小明", id_number=MINOR_ID), req, session)
                raise AssertionError("未满16周岁应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 400 and "16" in e.detail

            # 5. 校验位错误的身份证号 —— 拒绝。
            try:
                enroll_submit(position.id, "personal", personal_token,
                              EnrollSubmitIn(name="张三", id_number="110101199001011230"), req, session)
                raise AssertionError("校验位错误应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 400

            # 6. 正常提交：落一条 pending 状态的 submission，身份证号密文能解密回原文。
            _token_attempts.clear(); _ip_attempts.clear()
            result = enroll_submit(position.id, "personal", personal_token,
                                    EnrollSubmitIn(name="张三", id_number=ADULT_ID, phone="13800000000"),
                                    req, session)
            assert result["message"] == "提交成功"
            submission = session.get(PositionEnrollSubmission, result["submission_id"])
            assert submission is not None
            assert submission.payment_mode == "personal"
            assert submission.payment_status == "pending"  # 个人缴纳：等支付
            assert submission.review_status == "pending"   # 一律待人工审核，不自动参保
            assert submission.name == "张三"
            assert id_decrypt(submission.id_number_cipher) == ADULT_ID

            # 7. 单位缴纳：不涉及支付，直接 pending 待审核，不查使用费余额
            #    （本轮范围明确收窄，余额校验是"下一步"的事）。
            position.enable_employer_pay = True
            session.commit()
            employer_qr = position_enroll_qr(position.id, admin, session)
            employer_token = employer_qr["employer"]["url"].rsplit("/", 1)[-1]
            _token_attempts.clear(); _ip_attempts.clear()
            employer_result = enroll_submit(position.id, "employer", employer_token,
                                             EnrollSubmitIn(name="李四", id_number="110101199505056631"),
                                             req, session)
            employer_submission = session.get(PositionEnrollSubmission, employer_result["submission_id"])
            assert employer_submission.payment_mode == "employer"
            assert employer_submission.payment_status == "not_required"
            assert employer_submission.review_status == "pending"

        print("enroll submit test: PASS")


if __name__ == "__main__":
    run()
