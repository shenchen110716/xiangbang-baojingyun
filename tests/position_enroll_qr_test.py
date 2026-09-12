"""扫码参保：岗位二维码生成 + 无状态签名 token 的失效/校验行为。

覆盖：
- 未开启的缴费方式返回 enabled=False，不生成二维码
- 开启的缴费方式返回可用的 url + base64 PNG，且 url 里的 token 能通过校验
- "重新生成二维码"把 enroll_token_version 加一后，旧 token 立刻校验失败，
  新返回的 url 用新 token 能校验通过
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enroll-qr-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.enroll_tokens import verify_enroll_token
        from backend.models import ActualEmployer, Enterprise, User, WorkPosition
        from backend.routers.positions import position_enroll_qr, regenerate_position_enroll_qr

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = Enterprise(name="扫码参保测试企业", kind="企业", contact="c", phone="p",
                                     status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位",
                                       credit_code="91110108551385082Q", status="active")
            session.add(employer); session.commit(); session.refresh(employer)
            position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                     actual_employer=employer.name, name="测试岗位", status="approved",
                                     enable_personal_pay=True, enable_employer_pay=False)
            session.add(position); session.commit(); session.refresh(position)

            # 1. 只开了个人缴纳：personal 有码，employer 没有。
            result = position_enroll_qr(position.id, admin, session)
            assert result["personal"]["enabled"] is True
            assert result["personal"]["url"] and result["personal"]["qr_base64"]
            assert result["employer"]["enabled"] is False
            assert result["employer"]["url"] is None and result["employer"]["qr_base64"] is None

            # 2. url 里的 token 能通过校验（用当前 enroll_token_version）。
            old_url = result["personal"]["url"]
            old_token = old_url.rsplit("/", 1)[-1]
            assert verify_enroll_token(position.id, "personal", position.enroll_token_version, old_token) is True

            # 3. 重新生成后，旧 token 对不上新版本号了；新返回的 token 校验通过。
            regenerated = regenerate_position_enroll_qr(position.id, admin, session)
            session.refresh(position)
            assert position.enroll_token_version == 1
            assert verify_enroll_token(position.id, "personal", position.enroll_token_version, old_token) is False
            new_token = regenerated["personal"]["url"].rsplit("/", 1)[-1]
            assert verify_enroll_token(position.id, "personal", position.enroll_token_version, new_token) is True
            assert new_token != old_token

        print("position enroll qr test: PASS")


if __name__ == "__main__":
    run()
