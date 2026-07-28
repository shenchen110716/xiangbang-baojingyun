"""Regression: 参保员工姓名/身份证号一致性校验（用户反馈 2026-07-28 第 3 条，
2026-07-29 澄清范围）.

This file previously tested an exclusivity rule (same id_number can't be
active/pending in two enterprises at once, can't be duplicated within one
enterprise). The user corrected that assumption: this is a 灵活用工/gig
platform — one person can legitimately hold multiple different insurance
policies within one enterprise (to raise coverage), and the same person can
be simultaneously active/pending across *different* enterprises (moonlighting
on the same day). Blocking either of those would have broken real business
flows.

The only thing worth blocking is a data-quality problem: the same id_number
resolving to two different names anywhere in the system, which is almost
always a typo and would cause a workplace-injury claim to not match the
right person. That's what _assert_id_number_matches_name enforces, and this
file now tests that narrower rule instead.
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

            # 2. 同一人、同一单位再参加一次（不同险种加大保额）：不应该被当成
            #    重复参保拦下，姓名和身份证号都对得上就该放行。
            second_policy = add_person(PersonIn(enterprise_id=ent_a.id, name="张三", id_number=id_number), admin, session)
            assert second_policy["id"] is not None and second_policy["id"] != person["id"], \
                "同一单位内同一人应该允许同时参加不同险种，不能被当成重复参保拦下"

            # 3. 同一身份证号在乙单位（不同单位）新增参保，姓名一致：灵活用工场景下
            #    同一天在不同单位兼职、同时在保/待生效是正常业务，不能拦。
            moonlighting = add_person(PersonIn(enterprise_id=ent_b.id, name="张三", id_number=id_number), admin, session)
            assert moonlighting["id"] is not None, "跨单位同时在保（兼职）应该允许，不该被当成异常拦下"

            # 4. 同一身份证号，但姓名对不上：这是真正要拦的数据质量问题——身份证号
            #    或姓名有一处打错了，放过去将来出工伤理赔时会对不上人。
            try:
                add_person(PersonIn(enterprise_id=ent_b.id, name="张三丰", id_number=id_number), admin, session)
                raise AssertionError("同一身份证号对应不同姓名应该被拒绝（数据质量校验，不是排他校验）")
            except HTTPException as e:
                assert e.status_code == 409, f"应返回 409，实际 {e.status_code}"
                assert "张三" in e.detail, f"错误信息应该指出系统里已登记的姓名，实际：{e.detail}"

            # 5. update_person 把身份证号改成一个"姓名对不上"的号码，也要拦。
            other_person = add_person(PersonIn(enterprise_id=ent_b.id, name="李四", id_number="340123199001019985"), admin, session)
            try:
                update_person(person["id"], PersonUpdate(id_number="340123199001019985"), admin, session)
                raise AssertionError("update_person 改成姓名对不上的身份证号，应该被拒绝")
            except HTTPException as e:
                assert e.status_code == 409, f"应返回 409，实际 {e.status_code}"

            # 6. update_person 改成姓名一致的号码（哪怕对应另一单位在保的人），
            #    应该放行——这不是排他校验。
            renamed = update_person(person["id"], PersonUpdate(id_number=id_number, name="张三"), admin, session)
            assert renamed["id"] == person["id"]

            # 7. bulk_add_people（批量拍照/表格添加）遵守同一条姓名一致性规则：
            #    批次里一行的身份证号在系统里对应了不同姓名才报错，纯粹的跨单位/
            #    同单位重复不再是错误。
            position = WorkPosition(enterprise_id=ent_a.id, name="批量测试岗位", occupation_class="1-4类", status="approved")
            session.add(position); session.commit(); session.refresh(position)
            bulk_result = bulk_add_people(
                BulkPersonIn(enterprise_id=ent_a.id, position_id=position.id, rows=[
                    BulkPersonRow(name="王五", id_number="340123199001019993"),
                    BulkPersonRow(name="张三", id_number=id_number),  # 姓名对得上，允许同一人再添加
                ]),
                admin, session,
            )
            assert bulk_result["ok"] is True, f"姓名对得上时批量添加应该成功：{bulk_result}"
            assert bulk_result["created"] == 2

            # 8. 批次里出现姓名对不上的行，只有那一行报错并整批回滚（bulk_add_people
            #    现有行为：errors 非空就整体不落库）。
            bulk_bad = bulk_add_people(
                BulkPersonIn(enterprise_id=ent_a.id, position_id=position.id, rows=[
                    BulkPersonRow(name="赵六", id_number="340123199001019002"),
                    BulkPersonRow(name="张三丰", id_number=id_number),  # 姓名对不上，应报错
                ]),
                admin, session,
            )
            assert bulk_bad["ok"] is False, "批次里有姓名不一致的行时，整批应该失败"
            assert any("张三" in e["message"] for e in bulk_bad["errors"]), f"错误信息里应该指出已登记的姓名：{bulk_bad['errors']}"
            assert session.scalar(select(InsuredPerson.id).where(InsuredPerson.id_number == "340123199001019002")) is None, \
                "整批回滚后，批次里其他本来合法的行也不应该落库"

    print("insured cross-enterprise uniqueness test: PASS")


if __name__ == "__main__":
    run()
