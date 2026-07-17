"""Agent portal API wiring (v4.2 §14.4, §17.1).

The service-level leakage and settlement rules are covered by
agent_portal_leakage_test and agent_settlement_service_test. This test pins the
API surface: every endpoint is salesperson-gated, and list/summary/export are
derived from the one shared query so a total cannot drift from its detail.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "portal-api-test")

from sqlalchemy import create_engine
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import AgentCommission, Enterprise, InsurancePlan, User
from backend.routers import agent_portal as portal
from backend.services.agent_portal_query import CommissionFilters, commission_rows, commission_summary


def run() -> None:
    _test_every_endpoint_is_salesperson_gated()
    _test_summary_total_matches_detail_rows()
    print("agent portal api tests passed")


def _test_every_endpoint_is_salesperson_gated():
    """§17.1 门户全部端点仅业务员可访问——直接调函数会绕过依赖注入，
    所以断言路由确实挂了门禁，而不是只测函数。"""
    ungated = [r.path for r in portal.router.routes if not r.dependencies]
    assert not ungated, f"这些门户端点缺少角色门禁：{ungated}"
    assert len(portal.router.routes) == 8, len(portal.router.routes)
    print("  all endpoints gated ok")


def _test_summary_total_matches_detail_rows():
    """§14.4 列表、汇总、导出共用同一查询——汇总总额必须等于明细求和。"""
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        ent = Enterprise(name="一致性企业")
        session.add(ent)
        session.flush()
        agent = User(username="consistency_agent", password_hash="x", name="业务员",
                     role="salesperson")
        session.add(agent)
        session.flush()
        for i in range(3):
            plan = InsurancePlan(name=f"产品{i}", insurer="保司", price=100,
                                 commission_rate=0.3, profit_amount=10, status="active")
            session.add(plan)
            session.flush()
            session.add(AgentCommission(agent_id=agent.id, enterprise_id=ent.id,
                                        plan_id=plan.id, rate=0.1, mode="rebate",
                                        status="active"))
        session.flush()

        rows = commission_rows(session, agent.id, CommissionFilters())
        summary = commission_summary(session, agent.id, CommissionFilters())
        assert round(sum(r["amount"] for r in rows), 2) == summary["estimated_total"], \
            "汇总总额必须等于明细行求和"
    print("  summary matches detail ok")


if __name__ == "__main__":
    run()
