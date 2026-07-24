"""Shared insurer-portal ownership checks (2026-07-24 design §范围边界).

Every module the insurer portal touches (positions, policies, claims,
invoices) needs the same question answered: does this record belong to the
caller's insurer_id? Centralized here so the join-chain logic (especially the
claim → policy → plan → insurer_id resolution, which must match
claim_payload()'s fallback exactly) is written once.
"""
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Claim, InsurancePlan, InsuredPerson, Policy, User


def assert_plan_belongs_to_insurer(session: Session, user: User, plan_id: Optional[int]) -> None:
    """role=='insurer' 专用越权检查：目标方案必须挂在自己 insurer_id 下。
    非 insurer 角色直接放行——这不是身份检查，是范围检查。"""
    if user.role != "insurer":
        return
    if not plan_id:
        raise HTTPException(403, "无权操作未指定保险方案的记录")
    plan = session.get(InsurancePlan, plan_id)
    if not plan or plan.insurer_id != user.insurer_id:
        raise HTTPException(403, "无权操作其他保险公司的方案")


def insurer_plan_ids(session: Session, insurer_id: int) -> set[int]:
    return set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))


def claim_insurer_id(claim: Claim, session: Session) -> Optional[int]:
    """理赔案件归属的 insurer_id。和 services/claims.py 里 claim_payload() 的保单
    解析链路保持一致：优先 claim.policy_id，为空则退回被保险人当前 policy_id。"""
    policy_id = claim.policy_id
    if not policy_id:
        person = session.get(InsuredPerson, claim.person_id)
        policy_id = person.policy_id if person else None
    if not policy_id:
        return None
    policy = session.get(Policy, policy_id)
    if not policy:
        return None
    plan = session.get(InsurancePlan, policy.plan_id)
    return plan.insurer_id if plan else None
