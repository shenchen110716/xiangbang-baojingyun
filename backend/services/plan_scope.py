"""Which InsurancePlan rows an enterprise account is allowed to see/pick.

Shared by GET /plans (routers/plans.py) and work-position submission
(routers/positions.py, 2026-07-24 起企业新增岗位时可直接选定保司产品) so the
"这家企业能用哪些方案" 口径只有一处定义，不会两边各写一遍慢慢跑偏。
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import AgentCommission, WorkPosition


def enterprise_selectable_plan_ids(session: Session, enterprise_id: int) -> set[int]:
    from_commission = set(session.scalars(
        select(AgentCommission.plan_id).where(AgentCommission.enterprise_id == enterprise_id)))
    from_positions = set(session.scalars(
        select(WorkPosition.plan_id).where(
            WorkPosition.enterprise_id == enterprise_id, WorkPosition.plan_id.is_not(None))))
    return from_commission | from_positions
