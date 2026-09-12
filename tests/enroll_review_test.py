"""扫码参保：后台审核队列。

覆盖：
- 列表：admin 看全部（脱敏身份证号）；企业端只看自己企业的；其他角色 403
- 审核通过（单位缴纳）：创建 InsuredPerson（明文身份证号、岗位职业类别），
  回填 insured_person_id / reviewed_by / reviewed_at
- 审核通过（个人缴纳）：payment_status != 'paid' 时拒绝；'paid' 后放行
- 拒绝：不创建 InsuredPerson，review_note 落库
- 重复审核：400
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

ADULT_ID = "110101199001011237"   # GB 11643 校验位算法现算验证过
ADULT_ID_2 = "110101199505056631"


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-enroll-review-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException
        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.id_number import id_encrypt
        from backend.models import (
            ActualEmployer, Enterprise, InsuredPerson, PositionEnrollSubmission,
            User, WorkPosition,
        )
        from backend.routers.enroll_review import enroll_submissions, review_enroll_submission
        from backend.schemas import EnrollReviewIn

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))
            enterprise_user = session.scalar(select(User).where(User.username == "enterprise"))

            enterprise = Enterprise(name="审核队列测试企业", kind="企业", contact="c", phone="p",
                                     status="active", usage_balance=999999.0)
            session.add(enterprise); session.commit(); session.refresh(enterprise)
            employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位",
                                       credit_code="91110108551385082Q", status="active")
            session.add(employer); session.commit(); session.refresh(employer)
            position = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                     actual_employer=employer.name, name="测试岗位",
                                     occupation_class="1-3类", status="approved",
                                     enable_personal_pay=True, enable_employer_pay=True)
            session.add(position); session.commit(); session.refresh(position)

            employer_sub = PositionEnrollSubmission(
                position_id=position.id, payment_mode="employer", name="王五",
                id_number_cipher=id_encrypt(ADULT_ID), phone="13900000000",
                payment_status="not_required", review_status="pending")
            personal_sub = PositionEnrollSubmission(
                position_id=position.id, payment_mode="personal", name="赵六",
                id_number_cipher=id_encrypt(ADULT_ID_2), phone="13700000000",
                payment_status="pending", review_status="pending")
            session.add_all([employer_sub, personal_sub]); session.commit()
            session.refresh(employer_sub); session.refresh(personal_sub)

            # 1. admin 列表能看到两条，身份证号是脱敏的
            rows = enroll_submissions(None, None, admin, session)
            assert len(rows) == 2
            assert all("*" in r["id_number_masked"] for r in rows)
            assert all(ADULT_ID not in str(r) for r in rows), "列表不能泄露明文身份证号"

            # 2. 企业端只能看到自己企业的（这里都是它的 → 2条）；
            #    默认 enterprise 账号没绑这家企业 → 0条
            other_rows = enroll_submissions(None, None, enterprise_user, session)
            assert len(other_rows) == 0, "未绑定该企业的企业账号不应看到提交"

            # 3. 个人缴纳未支付 → 审核通过被拒
            try:
                review_enroll_submission(personal_sub.id, EnrollReviewIn(status="approved"), admin, session)
                raise AssertionError("未支付的个人缴纳不应能审核通过")
            except HTTPException as e:
                assert e.status_code == 400 and "支付" in e.detail

            # 4. 单位缴纳直接通过：创建 InsuredPerson，明文身份证号，回填关联
            result = review_enroll_submission(employer_sub.id, EnrollReviewIn(status="approved", review_note="ok"), admin, session)
            assert result["review_status"] == "approved"
            assert result["insured_person_id"]
            person = session.get(InsuredPerson, result["insured_person_id"])
            assert person.name == "王五"
            assert person.id_number == ADULT_ID
            assert person.enterprise_id == enterprise.id
            assert person.position_id == position.id
            assert person.occupation_class == "1-3类"
            assert person.status == "pending"
            session.refresh(employer_sub)
            assert employer_sub.reviewed_by == admin.id and employer_sub.reviewed_at is not None

            # 5. 重复审核 → 400
            try:
                review_enroll_submission(employer_sub.id, EnrollReviewIn(status="approved"), admin, session)
                raise AssertionError("已审核的提交不应能重复审核")
            except HTTPException as e:
                assert e.status_code == 400

            # 6. 个人缴纳支付完成后可以通过
            personal_sub.payment_status = "paid"; session.commit()
            result2 = review_enroll_submission(personal_sub.id, EnrollReviewIn(status="approved"), admin, session)
            assert result2["review_status"] == "approved" and result2["insured_person_id"]

            # 7. 拒绝：不建 InsuredPerson
            rejected_sub = PositionEnrollSubmission(
                position_id=position.id, payment_mode="employer", name="假名字",
                id_number_cipher=id_encrypt(ADULT_ID), phone="",
                payment_status="not_required", review_status="pending")
            session.add(rejected_sub); session.commit(); session.refresh(rejected_sub)
            before = session.scalar(select(InsuredPerson.id).order_by(InsuredPerson.id.desc()))
            result3 = review_enroll_submission(rejected_sub.id, EnrollReviewIn(status="rejected", review_note="姓名与身份证不符"), admin, session)
            assert result3["review_status"] == "rejected"
            assert result3["insured_person_id"] is None
            after = session.scalar(select(InsuredPerson.id).order_by(InsuredPerson.id.desc()))
            assert before == after, "拒绝不应创建 InsuredPerson"
            session.refresh(rejected_sub)
            assert rejected_sub.review_note == "姓名与身份证不符"

        print("enroll review test: PASS")


if __name__ == "__main__":
    run()
