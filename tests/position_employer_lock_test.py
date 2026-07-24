"""企业端不能修改/删除已有参保员工的岗位/实际用工单位（7-24 反馈）。"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-position-lock-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import ActualEmployer, Enterprise, InsuredPerson, User, WorkPosition
        from backend.schemas import ActualEmployerUpdate, PositionIn
        from backend.routers.positions import delete_position, positions, update_actual_employer, update_position

        startup()

        with SessionLocal() as session:
            ent = Enterprise(name="锁定测试企业", kind="企业", contact="", phone="", status="active")
            session.add(ent); session.commit(); session.refresh(ent)

            enterprise_user = User(username="lock_test_ent", password_hash="x", name="lock_test_ent", role="enterprise",
                                    enterprise_id=ent.id, is_owner=True, active=True, status="active")
            session.add(enterprise_user); session.commit(); session.refresh(enterprise_user)

            employer_a = ActualEmployer(enterprise_id=ent.id, name="用工单位A", status="active")
            employer_b = ActualEmployer(enterprise_id=ent.id, name="用工单位B", status="active")
            session.add_all([employer_a, employer_b]); session.commit()
            session.refresh(employer_a); session.refresh(employer_b)

            # 岗位1：已定类但没有员工 —— 应该仍然可以编辑
            empty_position = WorkPosition(enterprise_id=ent.id, actual_employer_id=employer_a.id,
                                           actual_employer=employer_a.name, name="空岗位", status="approved",
                                           occupation_class="4类")
            # 岗位2：已定类且有在保员工 —— 不能编辑/删除
            occupied_position = WorkPosition(enterprise_id=ent.id, actual_employer_id=employer_a.id,
                                              actual_employer=employer_a.name, name="占用岗位", status="approved",
                                              occupation_class="4类")
            # 岗位3：已定类，唯一关联员工已停保 —— 应该仍然可以编辑
            stopped_only_position = WorkPosition(enterprise_id=ent.id, actual_employer_id=employer_b.id,
                                                  actual_employer=employer_b.name, name="历史岗位", status="approved",
                                                  occupation_class="4类")
            session.add_all([empty_position, occupied_position, stopped_only_position])
            session.commit()
            for p in (empty_position, occupied_position, stopped_only_position):
                session.refresh(p)

            session.add(InsuredPerson(enterprise_id=ent.id, name="在保员工", id_number="340123199001019993",
                                       position_id=occupied_position.id, status="active"))
            session.add(InsuredPerson(enterprise_id=ent.id, name="已停保员工", id_number="340123199001019985",
                                       position_id=stopped_only_position.id, status="stopped"))
            session.commit()

            # GET /positions 暴露 has_active_people，前端按钮据此显隐
            rows = {row["name"]: row for row in positions(enterprise_user, session)}
            assert rows["空岗位"]["has_active_people"] is False
            assert rows["占用岗位"]["has_active_people"] is True
            assert rows["历史岗位"]["has_active_people"] is False, "唯一关联员工已停保，不应算作有参保员工"

            # 空岗位：企业端仍可修改（哪怕已定类）
            updated = update_position(empty_position.id, PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer_b.id, actual_employer=employer_b.name, name="空岗位-改名",
            ), enterprise_user, session)
            assert updated["actual_employer_id"] == employer_b.id and updated["name"] == "空岗位-改名"

            # 有在保员工的岗位：修改和删除都必须拒绝
            try:
                update_position(occupied_position.id, PositionIn(
                    enterprise_id=ent.id, actual_employer_id=employer_b.id, actual_employer=employer_b.name, name="占用岗位-改名",
                ), enterprise_user, session)
                raise AssertionError("occupied position update must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            try:
                delete_position(occupied_position.id, enterprise_user, session)
                raise AssertionError("occupied position delete must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            # 唯一关联员工已停保的岗位：企业端仍可修改
            updated_history = update_position(stopped_only_position.id, PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer_b.id, actual_employer=employer_b.name, name="历史岗位-改名",
            ), enterprise_user, session)
            assert updated_history["name"] == "历史岗位-改名"

            # 实际用工单位：employer_a 下面有在保员工（占用岗位），不能改名
            try:
                update_actual_employer(employer_a.id, ActualEmployerUpdate(name="用工单位A-改名"), enterprise_user, session)
                raise AssertionError("employer with active people must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            # employer_b 名下都是空岗位/停保历史，可以改名
            updated_employer = update_actual_employer(employer_b.id, ActualEmployerUpdate(name="用工单位B-改名"), enterprise_user, session)
            assert updated_employer["name"] == "用工单位B-改名"

        print("position/employer lock test: PASS")


if __name__ == "__main__":
    run()
