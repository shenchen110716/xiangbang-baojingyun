"""Smoke test: public enterprise self-signup — apply, review, login.

Covers the new POST /enterprises/apply flow end to end: a pending
Enterprise + inactive owner User get created together, duplicate
credit_code submissions are rejected, and approving the application
(PATCH /enterprises/{id}/status?status=approved) activates the owner
account so it can log in — while rejecting leaves it inactive.
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
        from backend.routers.auth import login
        from backend.routers.enterprises import apply_enterprise, enterprise_status
        from backend.schemas import EnterpriseApplyIn, LoginIn

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

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

            try:
                login(LoginIn(username="apply_owner", password="pass1234", portal="enterprise"), session)
                raise AssertionError("审核通过前不应该能登录")
            except HTTPException as e:
                assert e.status_code == 403, f"未激活账号登录应返回 403，实际 {e.status_code}"

            enterprise_status(enterprise.id, "approved", admin, session)
            session.refresh(owner)
            assert owner.active is True, "审核通过后企业主账号应该被激活"

            token = login(LoginIn(username="apply_owner", password="pass1234", portal="enterprise"), session)
            assert token.access_token, "审核通过后应该能用申请时的账号密码登录"

            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="被拒单位", credit_code="91XBBZP0002",
                    contact="赵拒绝", phone="13700000003",
                    username="apply_owner_rejected", password="pass1234",
                ),
                session,
            )
            rejected_enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "被拒单位"))
            enterprise_status(rejected_enterprise.id, "rejected", admin, session)
            rejected_owner = session.scalar(select(User).where(User.username == "apply_owner_rejected"))
            assert rejected_owner.active is False, "被拒绝的申请，账号必须保持不能登录"

    print("enterprise apply smoke test: PASS")


if __name__ == "__main__":
    run()
