"""发票抬头/税号必填 + 历史记录复用（用户反馈 2026-07-29）.

- 申请发票时发票抬头和纳税人识别号必须填写完整，否则拒绝创建。
- GET /invoices/titles 按该单位历史用过的抬头/税号组合去重返回，最近一次
  用的排最前，供申请表单自动带出/多条时下拉选择。
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-invoice-titles-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi.testclient import TestClient
        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User

        startup()
        client = TestClient(app)
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise = Enterprise(name="发票抬头测试企业", kind="企业", contact="c", phone="p",
                                   status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            enterprise_id = enterprise.id
            admin_login = client.post("/api/auth/login", json={"username": admin.username, "password": "admin123", "portal": "admin"})
        headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}

        # 1. 没填税号：拒绝创建，不是静默放行成空字符串。
        rejected = client.post("/api/invoices", headers=headers, json={
            "enterprise_id": enterprise_id, "account": "premium", "amount": 100, "title": "测试抬头甲",
        })
        assert rejected.status_code == 422, rejected.text

        # 2. 没填抬头同理拒绝。
        rejected2 = client.post("/api/invoices", headers=headers, json={
            "enterprise_id": enterprise_id, "account": "premium", "amount": 100, "title": "", "tax_no": "91330000MA1AAAAA1A",
        })
        assert rejected2.status_code == 422, rejected2.text

        # 3. 填完整才能创建。
        first = client.post("/api/invoices", headers=headers, json={
            "enterprise_id": enterprise_id, "account": "premium", "amount": 100,
            "title": "测试抬头甲", "tax_no": "91330000MA1AAAAA1A",
        })
        assert first.status_code == 200, first.text

        empty_history = client.get("/api/invoices/titles", headers=headers, params={"enterprise_id": enterprise_id})
        # 刚创建那一条已经能在历史里查到了。
        assert empty_history.status_code == 200
        assert empty_history.json() == [{"title": "测试抬头甲", "tax_no": "91330000MA1AAAAA1A"}]

        # 4. 同一单位换一个抬头再申请一次：历史应该按最近使用排序，去重不
        #    重复列出同一组合。
        second = client.post("/api/invoices", headers=headers, json={
            "enterprise_id": enterprise_id, "account": "usage", "amount": 50,
            "title": "测试抬头乙", "tax_no": "91330000MA2BBBBB2B",
        })
        assert second.status_code == 200, second.text
        third = client.post("/api/invoices", headers=headers, json={
            "enterprise_id": enterprise_id, "account": "premium", "amount": 200,
            "title": "测试抬头甲", "tax_no": "91330000MA1AAAAA1A",
        })
        assert third.status_code == 200, third.text

        history = client.get("/api/invoices/titles", headers=headers, params={"enterprise_id": enterprise_id}).json()
        assert history == [
            {"title": "测试抬头甲", "tax_no": "91330000MA1AAAAA1A"},
            {"title": "测试抬头乙", "tax_no": "91330000MA2BBBBB2B"},
        ], history

    print("invoice titles test: PASS")


if __name__ == "__main__":
    run()
