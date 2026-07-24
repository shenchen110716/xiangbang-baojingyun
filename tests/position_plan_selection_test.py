"""企业新增/编辑岗位时可以直接选定意向保司产品（2026-07-24 反馈：之前企业端
一律被强制清空成 None，只能等平台/保司审核时才分派）。"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-position-plan-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"

        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import (
            ActualEmployer, AgentCommission, Enterprise, InsurancePlan, Insurer, User, WorkPosition,
        )
        from backend.schemas import PositionIn
        from backend.routers.positions import add_position, update_position

        startup()

        with SessionLocal() as session:
            ent = Enterprise(name="选产品测试企业", kind="企业", contact="", phone="", status="active")
            session.add(ent); session.commit(); session.refresh(ent)

            enterprise_user = User(username="plan_pick_ent", password_hash="x", name="plan_pick_ent", role="enterprise",
                                    enterprise_id=ent.id, is_owner=True, active=True, status="active")
            admin = User(username="plan_pick_admin", password_hash="x", name="平台", role="admin", active=True, status="active")
            session.add_all([enterprise_user, admin]); session.commit()
            session.refresh(enterprise_user); session.refresh(admin)

            insurer = Insurer(name="选产品测试保司"); session.add(insurer); session.commit(); session.refresh(insurer)
            own_plan = InsurancePlan(insurer="选产品测试保司", name="本企业可选方案", insurer_id=insurer.id)
            other_plan = InsurancePlan(insurer="选产品测试保司", name="无关方案", insurer_id=insurer.id)
            session.add_all([own_plan, other_plan]); session.commit()
            session.refresh(own_plan); session.refresh(other_plan)

            # 企业和 own_plan 之间要有 AgentCommission 关系，才算"可选"——和
            # GET /plans 给企业端返回的产品列表同一份口径。
            session.add(AgentCommission(agent_id=admin.id, enterprise_id=ent.id, plan_id=own_plan.id,
                                         rate=0.1, mode="rebate", sale_price=100.0, status="active"))
            session.commit()

            employer = ActualEmployer(enterprise_id=ent.id, name="选产品用工单位", status="active")
            session.add(employer); session.commit(); session.refresh(employer)

            # 新增岗位时直接选定意向产品。
            created = add_position(PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                name="新岗位", plan_id=own_plan.id,
            ), enterprise_user, session)
            assert created["plan_id"] == own_plan.id, "企业选的方案应该被保留，不能被强行清空"
            assert created["occupation_class"] == "待定", "职业类别依然只能由保司审核确定"

            # 不能选一个自己不可见的方案。
            try:
                add_position(PositionIn(
                    enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                    name="越权岗位", plan_id=other_plan.id,
                ), enterprise_user, session)
                raise AssertionError("selecting an unrelated plan must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            # PositionIn 是整表单一次性提交（和 admin 分支的 data.plan_id 语义一致，
            # 不是增量 PATCH），所以前端表单要把当前已选产品一起带上再提交——这里
            # 模拟前端"编辑弹窗预填 plan_id，只改了名字"的真实提交内容：只要带上
            # 了 own_plan.id，就不会像过去那样被后端强行清空成 None。
            position_id = created["id"]
            updated = update_position(position_id, PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                name="新岗位-改名", plan_id=own_plan.id,
            ), enterprise_user, session)
            assert updated["plan_id"] == own_plan.id, "只是改个名字，之前选的产品不该被清空"

            cleared = update_position(position_id, PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                name="新岗位-改名", plan_id=None,
            ), enterprise_user, session)
            assert cleared["plan_id"] is None, "企业应该也能主动把已选产品清空，回到未认领状态"

            # 试图在编辑时越权改到别的方案，同样要拒绝。
            try:
                update_position(position_id, PositionIn(
                    enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                    name="新岗位-改名", plan_id=other_plan.id,
                ), enterprise_user, session)
                raise AssertionError("updating into an unrelated plan must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            # 管理员创建/编辑不受这条企业专属校验约束。
            admin_created = add_position(PositionIn(
                enterprise_id=ent.id, actual_employer_id=employer.id, actual_employer=employer.name,
                name="管理员新建岗位", occupation_class="1-3类", plan_id=other_plan.id,
            ), admin, session)
            assert admin_created["plan_id"] == other_plan.id
            assert admin_created["occupation_class"] == "1-3类"

        print("position plan selection test: PASS")


if __name__ == "__main__":
    run()
