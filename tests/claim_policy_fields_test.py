"""Claim 报案表单新增字段（policy_id/injury_part/payee_type）冒烟测试，7-23 反馈重设计。"""
import os
import sys
import tempfile
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-claim-fields-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from fastapi import HTTPException

        from backend.app import startup
        from backend.core.business_time import business_now
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember, User, WorkPosition
        from backend.schemas import ClaimIn, ClaimUpdate
        from backend.routers.claims import add_claim, update_claim

        startup()

        with SessionLocal() as session:
            ent_a = Enterprise(name="理赔字段测试企业A", kind="企业", contact="", phone="", status="active")
            ent_b = Enterprise(name="理赔字段测试企业B", kind="企业", contact="", phone="", status="active")
            session.add_all([ent_a, ent_b]); session.commit(); session.refresh(ent_a); session.refresh(ent_b)

            plan = InsurancePlan(insurer="测试保司", name="理赔字段测试方案", price=100, commission_rate=.2, status="active")
            session.add(plan); session.flush()

            position = WorkPosition(enterprise_id=ent_a.id, name="测试岗位", plan_id=plan.id, status="approved")
            session.add(position); session.flush()

            policy_a1 = Policy(policy_no="CLM-A1", enterprise_id=ent_a.id, plan_id=plan.id, status="active")
            policy_a2 = Policy(policy_no="CLM-A2", enterprise_id=ent_a.id, plan_id=plan.id, status="active")
            policy_b = Policy(policy_no="CLM-B1", enterprise_id=ent_b.id, plan_id=plan.id, status="active")
            session.add_all([policy_a1, policy_a2, policy_b]); session.flush()

            person = InsuredPerson(enterprise_id=ent_a.id, name="理赔字段测试员工", position_id=position.id,
                                    policy_id=policy_a1.id, status="active")
            session.add(person); session.flush()
            session.add(PolicyMember(policy_id=policy_a1.id, person_id=person.id,
                                      effective_at=business_now() - timedelta(days=2), status="active"))
            session.add(PolicyMember(policy_id=policy_a2.id, person_id=person.id,
                                      effective_at=business_now() - timedelta(days=1), status="active"))
            session.commit(); session.refresh(person)

            admin = session.query(User).filter(User.username == "admin").one()

            # 指定这次事故挂在 policy_a2（不是 person.policy_id 指向的 policy_a1）下，
            # claim_payload 应该按 claim.policy_id 显示，而不是退回到 person 当前保单。
            claim = add_claim(ClaimIn(
                enterprise_id=ent_a.id, person_id=person.id, policy_id=policy_a2.id,
                description="测试报案", accident_at="2026-01-01 09:00", accident_place="车间",
                injury_part="右手食指", payee_type="本人",
            ), admin, session)
            assert claim["policy_no"] == "CLM-A2", claim["policy_no"]
            assert claim["injury_part"] == "右手食指"
            assert claim["payee_type"] == "本人"

            # 保单不属于该投保单位必须拒绝，不能把事故挂到别的企业的保单下
            try:
                add_claim(ClaimIn(
                    enterprise_id=ent_a.id, person_id=person.id, policy_id=policy_b.id,
                    description="测试报案", accident_at="2026-01-01 09:00", accident_place="车间",
                ), admin, session)
                raise AssertionError("cross-enterprise policy_id must be rejected")
            except HTTPException as error:
                assert error.status_code == 400

            # 不指定 policy_id 时，仍然按 person 当前保单归档（向后兼容旧记录/旧流程）
            claim_no_policy = add_claim(ClaimIn(
                enterprise_id=ent_a.id, person_id=person.id,
                description="测试报案二", accident_at="2026-01-01 09:00", accident_place="车间",
            ), admin, session)
            assert claim_no_policy["policy_no"] == "CLM-A1", claim_no_policy["policy_no"]

            # 企业端可以事后修改受伤部位/收款人类型（和其他报案描述字段同一权限档位）
            updated = update_claim(claim["id"], ClaimUpdate(injury_part="腰部", payee_type="近亲属"), admin, session)
            assert updated["injury_part"] == "腰部" and updated["payee_type"] == "近亲属"

        print("claim policy/injury/payee fields test: PASS")


if __name__ == "__main__":
    run()
