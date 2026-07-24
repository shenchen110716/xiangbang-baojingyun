"""Smoke test: public enterprise self-signup — apply.

Covers the new POST /enterprises/apply flow: a pending Enterprise +
inactive owner User get created together, and duplicate credit_code
submissions are rejected. A later task extends this file to also cover
admin approval activating the owner account and login.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enterprise-apply-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.routers.enterprises import apply_enterprise
        from backend.schemas import EnterpriseApplyIn

        startup()
        with SessionLocal() as session:
            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="自助入驻测试单位", credit_code="91XBBZP0001",
                    contact="王申请", phone="13700000001",
                    username="apply_owner", password="pass1234",
                ),
                session,
            )
            enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "自助入驻测试单位"))
            assert enterprise is not None, "apply_enterprise 应该创建一条 Enterprise 记录"
            assert enterprise.status == "pending", f"新申请应处于待核验，实际 status={enterprise.status!r}"
            owner = session.scalar(select(User).where(User.username == "apply_owner"))
            assert owner is not None, "apply_enterprise 应该创建关联的企业主账号"
            assert owner.active is False, "审核通过前账号不能登录，实际 active=True"
            assert owner.is_owner is True and owner.enterprise_role == "owner", "首个账号必须是 owner"

            # Test: required field missing (empty phone)
            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="缺少电话单位", credit_code="91XBBZP0002",
                        contact="张申请", phone="",
                        username="apply_owner_no_phone", password="pass1234",
                    ),
                    session,
                )
                raise AssertionError("缺少必填字段应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 400, f"缺少必填字段应返回 400，实际 {e.status_code}"

            # Test: duplicate username
            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="重复账号单位", credit_code="91XBBZP0003",
                        contact="李重复", phone="13700000003",
                        username="apply_owner", password="pass1234",
                    ),
                    session,
                )
                raise AssertionError("重复账号应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复账号应返回 409，实际 {e.status_code}"

            # Test: duplicate credit_code
            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="重复单位", credit_code="91XBBZP0001",
                        contact="李重复", phone="13700000002",
                        username="apply_owner_dup", password="pass1234",
                    ),
                    session,
                )
                raise AssertionError("重复统一社会信用代码应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复申请应返回 409，实际 {e.status_code}"

    print("enterprise apply smoke test: PASS")


if __name__ == "__main__":
    run()
