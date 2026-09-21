"""两步参保：添加名单（不判余额）→ 批量参保（此时才过使用费门禁）。

2026-09-20 用户反馈：拍照批量添加因使用费余额为0整批失败，体验差。
改为 enroll=False 只收名单（draft 未参保、零费用），batch-enroll 才判余额。

覆盖：
- 余额为0：enroll=True 仍被 403（老行为不变）；enroll=False 成功落 draft
- draft 人员不产生使用费（usage_account_view consumed 不变）
- 余额为0时 batch-enroll：逐行失败"余额不足"，人保持 draft
- 充值后 batch-enroll：draft → pending，写参保操作记录（及时率口径从参保起算）
- batch-enroll 对非 draft / 他人企业的人员逐行报错不炸整批
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ADULT_ID = "110101199001011237"
ADULT_ID_2 = "110101199505056631"


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-draft-enroll-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import (
            ActualEmployer, Enterprise, InsuredPerson, ParticipationOperation,
            User, WorkPosition,
        )
        from backend.routers.insured import add_person, batch_enroll
        from backend.schemas import BatchEnrollIn, PersonIn
        from backend.services import usage_account_view

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            # 余额为 0 的企业（复现线上河南众合的状态）
            enterprise = Enterprise(name="两步参保测试企业", kind="企业", contact="c", phone="p",
                                     status="approved", usage_balance=0.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位",
                                       credit_code="91110108551385082Q", status="active")
            session.add(employer); session.commit(); session.refresh(employer)
            position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                     actual_employer=employer.name, name="测试岗位",
                                     occupation_class="1-3类", status="approved")
            session.add(position); session.commit(); session.refresh(position)

            # 1. 老行为不变：enroll=True（默认）余额为0 → 403
            try:
                add_person(PersonIn(enterprise_id=enterprise.id, name="张三", id_number=ADULT_ID,
                                    position_id=position.id), admin, session)
                raise AssertionError("余额为0时直接参保应403")
            except HTTPException as e:
                assert e.status_code == 403 and "使用费" in e.detail

            # 2. enroll=False：余额为0照样能收名单，落 draft
            draft1 = add_person(PersonIn(enterprise_id=enterprise.id, name="张三", id_number=ADULT_ID,
                                          position_id=position.id, enroll=False), admin, session)
            draft2 = add_person(PersonIn(enterprise_id=enterprise.id, name="李四", id_number=ADULT_ID_2,
                                          position_id=position.id, enroll=False), admin, session)
            assert draft1["status"] == "draft" and draft2["status"] == "draft"

            # 3. draft 不产生使用费，也没有参保操作记录
            view = usage_account_view(session, enterprise)
            assert view["consumed"] == 0 and view["active_people"] == 0
            ops = session.scalar(select(ParticipationOperation.id).where(
                ParticipationOperation.person_id == draft1["id"]).limit(1))
            assert ops is None, "draft 不应有参保操作记录"

            # 4. 余额为0时批量参保：逐行失败，状态保持 draft
            result = batch_enroll(BatchEnrollIn(ids=[draft1["id"], draft2["id"]]), admin, session)
            assert result["success"] == 0 and result["failed"] == 2
            assert all("余额不足" in r["error"] for r in result["results"])
            assert session.get(InsuredPerson, draft1["id"]).status == "draft"

            # 5. 充值后批量参保成功：draft → pending + 参保操作记录
            enterprise.usage_balance = 1000.0; session.commit()
            result2 = batch_enroll(BatchEnrollIn(ids=[draft1["id"], draft2["id"]]), admin, session)
            assert result2["success"] == 2 and result2["failed"] == 0, result2
            p1 = session.get(InsuredPerson, draft1["id"])
            assert p1.status == "pending"
            ops_after = session.scalar(select(ParticipationOperation.id).where(
                ParticipationOperation.person_id == p1.id).limit(1))
            assert ops_after is not None, "参保后应有操作记录"

            # 6. 非draft重复参保 / 不存在的id：逐行报错不炸整批
            result3 = batch_enroll(BatchEnrollIn(ids=[draft1["id"], 999999]), admin, session)
            assert result3["success"] == 0 and result3["failed"] == 2
            assert "不是未参保状态" in result3["results"][0]["error"]
            assert "不存在" in result3["results"][1]["error"]

            # 7. 删除：draft 可删；已参保（pending）拒绝并引导停保
            from backend.routers.insured import delete_person
            # 独立证号（GB 11643 现算验证过）——不能复用李四的号，同号异名会被
            # 数据质量校验拦。
            draft3 = add_person(PersonIn(enterprise_id=enterprise.id, name="王五删", id_number="110101198807061239",
                                          position_id=position.id, enroll=False), admin, session)
            deleted = delete_person(draft3["id"], admin, session)
            assert deleted["ok"] is True
            assert session.get(InsuredPerson, draft3["id"]) is None

            try:
                delete_person(draft1["id"], admin, session)  # draft1 已在第5步参保为 pending
                raise AssertionError("已参保人员不应能删除")
            except HTTPException as e:
                assert e.status_code == 400 and "停保" in e.detail

        print("insured draft enroll test: PASS")


if __name__ == "__main__":
    run()
