"""Regression: 参保员工姓名/身份证号唯一性（用户反馈 2026-07-28 第 3 条）.

Before this fix, add_person/update_person/bulk_add_people/employment-fact
import only checked id_number uniqueness *within one enterprise* (or, for
the old bulk_add_people path, checked *globally including stopped records*
— the opposite mistake, blocking legitimate re-enrollment after someone
changes employer). Neither caught the real risk: the same person actively
enrolled (pending/active) under two *different* enterprises at once, which
is exactly the kind of data error that causes disputes over which policy
should pay out on a workplace injury claim.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-cross-ent-unique-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, InsuredPerson, User, WorkPosition
        from backend.routers.insured import add_person, bulk_add_people, update_person
        from backend.schemas import BulkPersonIn, BulkPersonRow, PersonIn, PersonUpdate

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            ent_a = Enterprise(name="唯一性测试甲单位", kind="企业", contact="", phone="", status="active", usage_balance=999999.0)
            ent_b = Enterprise(name="唯一性测试乙单位", kind="企业", contact="", phone="", status="active", usage_balance=999999.0)
            session.add_all([ent_a, ent_b]); session.commit(); session.refresh(ent_a); session.refresh(ent_b)

            id_number = "340123199001018886"

            # 1. 甲单位正常参保一个人。
            person = add_person(PersonIn(enterprise_id=ent_a.id, name="张三", id_number=id_number), admin, session)
            assert person["id"] is not None

            # 2. 同一身份证号在乙单位（不同单位）新增参保：这个人在甲单位还是
            #    pending 状态（没审核也没停保），应该被拒绝，不能出现"两家单位
            #    同时参保同一个人"这种真实事故风险。
            try:
                add_person(PersonIn(enterprise_id=ent_b.id, name="张三", id_number=id_number), admin, session)
                raise AssertionError("同一人在另一单位处于在保/待生效状态时，跨单位重复参保应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 409, f"应返回 409，实际 {e.status_code}"
                assert "唯一性测试甲单位" in e.detail, f"错误信息应该指出具体是哪家单位占用的，实际：{e.detail}"

            # 3. update_person 把身份证号改成一个"在别的单位还在保"的号码，也要拦。
            other_active = add_person(PersonIn(enterprise_id=ent_b.id, name="李四", id_number="340123199001019985"), admin, session)
            try:
                update_person(person["id"], PersonUpdate(id_number="340123199001019985"), admin, session)
                raise AssertionError("update_person 改成别的单位在保人员的身份证号，应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 409, f"应返回 409，实际 {e.status_code}"

            # 4. 甲单位把张三停保后，乙单位应该能正常重新参保这个人——换工作是
            #    合法场景，不能因为历史记录就永久锁死这个身份证号。
            item = session.get(InsuredPerson, person["id"])
            item.status = "stopped"
            session.commit()
            rejoined = add_person(PersonIn(enterprise_id=ent_b.id, name="张三", id_number=id_number), admin, session)
            assert rejoined["id"] is not None, "原单位已停保后，应该允许在新单位重新参保"

            # 5. bulk_add_people（批量拍照/表格添加）同样要遵守这条规则：
            #    批次里有一行的身份证号在另一单位还在保，只有那一行报错，
            #    其他合法行不受影响——已知 bulk_add_people 现在整批失败就
            #    整批回滚（errors 非空直接 rollback），所以这里验证的是
            #    "有错误时整批不落库，且错误信息指向正确的行"。
            position = WorkPosition(enterprise_id=ent_a.id, name="批量测试岗位", occupation_class="1-4类", status="approved")
            session.add(position); session.commit(); session.refresh(position)
            bulk_result = bulk_add_people(
                BulkPersonIn(enterprise_id=ent_a.id, position_id=position.id, rows=[
                    BulkPersonRow(name="王五", id_number="340123199001019993"),
                    BulkPersonRow(name="李四", id_number="340123199001019985"),  # 乙单位在保中，应报错
                ]),
                admin, session,
            )
            assert bulk_result["ok"] is False, "批次里有跨单位冲突时，整批应该失败而不是部分成功"
            assert any("在保/待生效" in e["message"] for e in bulk_result["errors"]), f"错误信息里应该有跨单位冲突提示：{bulk_result['errors']}"
            assert session.scalar(select(InsuredPerson.id).where(InsuredPerson.id_number == "340123199001019993")) is None, \
                "整批回滚后，批次里其他本来合法的行也不应该落库"

    print("insured cross-enterprise uniqueness test: PASS")


if __name__ == "__main__":
    run()
