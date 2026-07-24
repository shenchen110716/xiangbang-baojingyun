"""Smoke test: public enterprise self-signup — apply, review, login.

Covers the new POST /enterprises/apply flow end to end: a pending
Enterprise + inactive owner User get created together, duplicate
credit_code submissions are rejected, and approving the application
(PATCH /enterprises/{id}/status?status=approved) activates the owner
account so it can log in — while rejecting leaves it inactive. Also
covers the anti-abuse additions: honeypot field and per-IP rate limit,
since this endpoint is public/unauthenticated and now linked from the
public marketing site.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class _FakeRequest:
    """Minimal stand-in for fastapi.Request — only what apply_enterprise reads."""
    def __init__(self, ip="127.0.0.1"):
        self.headers = {}
        self.client = type("Client", (), {"host": ip})()


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enterprise-apply-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from pydantic import ValidationError
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
                _FakeRequest("10.0.0.1"),
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
                    _FakeRequest("10.0.0.2"),
                    session,
                )
                raise AssertionError("重复统一社会信用代码应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复申请应返回 409，实际 {e.status_code}"

            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="缺字段单位", credit_code="91XBBZP0003",
                        contact="", phone="13700000004",
                        username="apply_owner_missing", password="pass1234",
                    ),
                    _FakeRequest("10.0.0.3"),
                    session,
                )
                raise AssertionError("联系人为空应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 400, f"必填项缺失应返回 400，实际 {e.status_code}"

            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="重复账号单位", credit_code="91XBBZP0004",
                        contact="孙重复账号", phone="13700000005",
                        username="apply_owner", password="pass1234",
                    ),
                    _FakeRequest("10.0.0.4"),
                    session,
                )
                raise AssertionError("重复账号名应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 409, f"重复账号名应返回 409，实际 {e.status_code}"

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
                _FakeRequest("10.0.0.5"),
                session,
            )
            rejected_enterprise = session.scalar(select(Enterprise).where(Enterprise.name == "被拒单位"))
            enterprise_status(rejected_enterprise.id, "rejected", admin, session)
            rejected_owner = session.scalar(select(User).where(User.username == "apply_owner_rejected"))
            assert rejected_owner.active is False, "被拒绝的申请，账号必须保持不能登录"

            try:
                EnterpriseApplyIn(
                    enterprise_name="超长账号单位", credit_code="91XBBZP0005",
                    contact="超长账号", phone="13700000006",
                    username="x" * 100, password="pass1234",
                )
                raise AssertionError("超长登录账号应该被 Pydantic 拒绝，但没有抛出异常")
            except ValidationError:
                pass

            try:
                EnterpriseApplyIn(
                    enterprise_name="弱密码单位", credit_code="91XBBZP0006",
                    contact="弱密码", phone="13700000007",
                    username="apply_owner_weakpw", password="123",
                )
                raise AssertionError("过短密码应该被 Pydantic 拒绝，但没有抛出异常")
            except ValidationError:
                pass

            # 蜜罐字段被填：假装成功，但不落库——机器人拿不到"被拒绝"的反馈信号。
            before_count = len(list(session.scalars(select(Enterprise))))
            result = apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="蜜罐机器人单位", credit_code="91XBBZPHONEY",
                    contact="机器人", phone="13700000099",
                    username="honeypot_bot", password="pass1234",
                    website="http://spam.example",
                ),
                _FakeRequest("10.0.0.6"),
                session,
            )
            assert result == {"message": "提交成功，请等待审核"}, "蜜罐触发时应返回和正常成功一样的响应"
            after_count = len(list(session.scalars(select(Enterprise))))
            assert after_count == before_count, "蜜罐触发时不应该真的创建 Enterprise 记录"
            assert session.scalar(select(User).where(User.username == "honeypot_bot")) is None, "蜜罐触发时不应该真的创建账号"

            # 同一 IP 短时间内超过限流阈值应该被拒绝，不同 IP 之间互不影响。
            rate_limit_ip = "10.0.0.200"
            for i in range(5):
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name=f"限流测试单位{i}", credit_code=f"91XBBZPRATE{i}",
                        contact="限流测试", phone="13700000100",
                        username=f"rate_owner_{i}", password="pass1234",
                    ),
                    _FakeRequest(rate_limit_ip),
                    session,
                )
            try:
                apply_enterprise(
                    EnterpriseApplyIn(
                        enterprise_name="限流测试单位超限", credit_code="91XBBZPRATEX",
                        contact="限流测试", phone="13700000101",
                        username="rate_owner_over", password="pass1234",
                    ),
                    _FakeRequest(rate_limit_ip),
                    session,
                )
                raise AssertionError("超过限流阈值应该被拒绝，但没有抛出异常")
            except HTTPException as e:
                assert e.status_code == 429, f"超过限流阈值应返回 429，实际 {e.status_code}"
            # 换一个 IP 立刻可以正常提交，证明限流是按 IP 隔离的，不是全局共享一个计数器。
            apply_enterprise(
                EnterpriseApplyIn(
                    enterprise_name="换IP不受限单位", credit_code="91XBBZPRATEY",
                    contact="限流测试", phone="13700000102",
                    username="rate_owner_other_ip", password="pass1234",
                ),
                _FakeRequest("10.0.0.201"),
                session,
            )

    print("enterprise apply smoke test: PASS")


if __name__ == "__main__":
    run()
