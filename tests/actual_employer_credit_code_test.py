"""Regression: 添加实际用工单位需要校验企业名称及信用代码的准确性
（用户反馈 2026-07-30 第 2 条）.

credit_code 之前是可选字段，留空或随便填几个字符都能直接落库，没有任何
格式/校验位检查。现在必填，且校验位错误（GB 32100-2015 统一社会信用代码
校验位算法）的一律拒绝，跟 id_number.py 对身份证号的处理是同一个思路。
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

VALID_CREDIT_CODE = "91110108551385082Q"
INVALID_CHECKSUM_CREDIT_CODE = "91110108551385082A"  # 格式对，最后一位校验位错


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-credit-code-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from pydantic import ValidationError
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.credit_code import is_valid_credit_code
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.routers.positions import add_actual_employer, update_actual_employer
        from backend.schemas import ActualEmployerIn, ActualEmployerUpdate

        # 1. 算法本身的正反例。
        assert is_valid_credit_code(VALID_CREDIT_CODE) is True
        assert is_valid_credit_code(INVALID_CHECKSUM_CREDIT_CODE) is False
        assert is_valid_credit_code("") is False
        assert is_valid_credit_code("太短了") is False

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = Enterprise(name="信用代码测试企业", kind="企业", contact="c", phone="p",
                                   status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)

            # 2. 不填 credit_code：schema 层直接拒绝（必填）。
            try:
                ActualEmployerIn(enterprise_id=enterprise.id, name="测试用工单位")
                raise AssertionError("credit_code 必填，留空应该在 schema 层就报错")
            except ValidationError:
                pass

            # 3. 填了但校验位不对：路由层拒绝。
            try:
                add_actual_employer(
                    ActualEmployerIn(enterprise_id=enterprise.id, name="测试用工单位",
                                    credit_code=INVALID_CHECKSUM_CREDIT_CODE),
                    admin, session)
                raise AssertionError("校验位错误的信用代码应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 400, e.status_code
                assert "信用代码" in e.detail

            # 4. 合法信用代码能正常创建。
            employer = add_actual_employer(
                ActualEmployerIn(enterprise_id=enterprise.id, name="测试用工单位",
                                credit_code=VALID_CREDIT_CODE),
                admin, session)
            assert employer["credit_code"] == VALID_CREDIT_CODE

            # 5. 更新时如果传了新的信用代码，同样要过校验位；不传（None）则不触发校验。
            try:
                update_actual_employer(employer["id"], ActualEmployerUpdate(credit_code=INVALID_CHECKSUM_CREDIT_CODE), admin, session)
                raise AssertionError("更新成校验位错误的信用代码应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 400, e.status_code

            renamed = update_actual_employer(employer["id"], ActualEmployerUpdate(contact="新联系人"), admin, session)
            assert renamed["contact"] == "新联系人", "不改信用代码时更新应该正常通过"

    print("actual employer credit code test: PASS")


if __name__ == "__main__":
    run()
