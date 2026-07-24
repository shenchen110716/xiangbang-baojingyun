"""同一投保单位内，操作员手机号唯一匹配（7-23 反馈：企业人员管理按手机号唯一匹配）。"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-operator-phone-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.schemas import OperatorIn, OperatorUpdate
        from backend.routers.operators import add_operator, update_operator

        startup()

        with SessionLocal() as session:
            ent_a = Enterprise(name="手机号测试企业A", kind="企业", contact="", phone="", status="active")
            ent_b = Enterprise(name="手机号测试企业B", kind="企业", contact="", phone="", status="active")
            session.add_all([ent_a, ent_b]); session.commit()
            session.refresh(ent_a); session.refresh(ent_b)

            owner_a = User(username="phone_test_owner_a", password_hash="x", name="owner_a", role="enterprise",
                            enterprise_id=ent_a.id, is_owner=True, active=True, status="active")
            session.add(owner_a); session.commit(); session.refresh(owner_a)

            first = add_operator(OperatorIn(username="phone_op_1", password="pass123", name="张三", phone="13800000001"), owner_a, session)
            assert first["phone"] == "13800000001"

            # 同一企业内手机号重复，创建必须拒绝
            try:
                add_operator(OperatorIn(username="phone_op_2", password="pass123", name="张三小号", phone="13800000001"), owner_a, session)
                raise AssertionError("duplicate phone within same enterprise must be rejected")
            except HTTPException as error:
                assert error.status_code == 409

            # 不同企业可以有相同手机号（同一个人挂靠多家单位的场景，不应该被这条规则误伤）
            owner_b = User(username="phone_test_owner_b", password_hash="x", name="owner_b", role="enterprise",
                            enterprise_id=ent_b.id, is_owner=True, active=True, status="active")
            session.add(owner_b); session.commit(); session.refresh(owner_b)
            cross_enterprise = add_operator(OperatorIn(username="phone_op_3", password="pass123", name="李四", phone="13800000001"), owner_b, session)
            assert cross_enterprise["phone"] == "13800000001"

            # 修改手机号撞车同企业已有的号码，必须拒绝
            second = add_operator(OperatorIn(username="phone_op_4", password="pass123", name="王五", phone="13800000002"), owner_a, session)
            try:
                update_operator(second["id"], OperatorUpdate(phone="13800000001"), owner_a, session)
                raise AssertionError("update to a duplicate phone must be rejected")
            except HTTPException as error:
                assert error.status_code == 409

            # 改成自己原来的号码（没有实际变化）应该放行，不能被自己绊住
            unchanged = update_operator(second["id"], OperatorUpdate(phone="13800000002"), owner_a, session)
            assert unchanged["phone"] == "13800000002"

        print("operator phone uniqueness test: PASS")


if __name__ == "__main__":
    run()
