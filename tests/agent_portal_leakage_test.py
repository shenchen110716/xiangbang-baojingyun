"""Agent portal isolation and pricing leakage (v4.2 §5.1, §17.1).

§5.1 was already breached in production once: strip_internal_pricing masked
only the enterprise role, so a salesperson could read the insurer settlement
price and the platform's profit. That fix (0caa07b) is a deny-list stopgap —
it hides today's known-bad fields. This phase owes the allow-list, which is
what actually holds: a field added to InsurancePlan tomorrow must not appear in
the portal by default.

Hence the exact-set assertion below. "One extra field is a leak" is not
pedantry — it is the only version of this test that survives a schema change.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "agent-portal-test")

from fastapi import HTTPException
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import AgentCommission, Enterprise, InsurancePlan, User
from backend.services.agent_settlements import (
    PORTAL_PRODUCT_FIELDS,
    portal_commission_summary,
    portal_products,
)

# 禁止出现在业务员响应中的字段（§5.1）。
FORBIDDEN = [
    "price", "cost_price", "settle_price", "profit_amount", "commission_rate",
    "markup_amount", "total_rebate", "insurance_base_price", "policy_floor_price",
    "insurer_settlement_price", "platform_margin_amount", "total_commission_rate",
    "total_commission_amount",
]


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="门户企业")
    session.add(ctx.enterprise)
    session.flush()
    ctx.agent_a = User(username="agent_a", password_hash="x", name="业务员甲",
                       role="salesperson")
    ctx.agent_b = User(username="agent_b", password_hash="x", name="业务员乙",
                       role="salesperson")
    session.add_all([ctx.agent_a, ctx.agent_b])
    session.flush()

    ctx.configured = InsurancePlan(
        name="已配置佣金的产品", insurer="保司甲", coverage="工伤保障",
        occupation_classes="1-3类", price=100, commission_rate=0.3,
        profit_amount=10, billing_mode="monthly", effective_mode="next_day",
        status="active")
    ctx.unconfigured = InsurancePlan(
        name="未配置佣金的产品", insurer="保司乙", coverage="意外保障",
        occupation_classes="1-4类", price=200, commission_rate=0.2,
        profit_amount=20, billing_mode="daily", effective_mode="immediate",
        status="active")
    session.add_all([ctx.configured, ctx.unconfigured])
    session.flush()

    session.add(AgentCommission(
        agent_id=ctx.agent_a.id, enterprise_id=ctx.enterprise.id,
        plan_id=ctx.configured.id, rate=0.1, mode="rebate", status="active"))
    session.flush()
    return ctx


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        ctx = _setup(session)
        _test_returns_all_sellable_products_even_without_a_relation(session, ctx)
        _test_response_is_an_exact_allow_list(session, ctx)
        _test_min_price_is_present_and_correct(session, ctx)
        _test_unconfigured_product_says_not_configured(session, ctx)
        _test_agent_id_in_the_query_is_ignored(session, ctx)

    print("agent portal leakage tests passed")


def _test_returns_all_sellable_products_even_without_a_relation(session, ctx):
    """§5.1 即使产品尚未为该业务员配置佣金关系也要返回。"""
    names = [p["name"] for p in portal_products(session, ctx.agent_a)]
    assert "未配置佣金的产品" in names, names
    assert "已配置佣金的产品" in names, names
    print("  all sellable products ok")


def _test_response_is_an_exact_allow_list(session, ctx):
    """白名单：多一个字段就算泄漏。"""
    for product in portal_products(session, ctx.agent_a):
        assert set(product) == PORTAL_PRODUCT_FIELDS, \
            f"意外字段: {sorted(set(product) - PORTAL_PRODUCT_FIELDS)}"
        for key in FORBIDDEN:
            assert key not in product, f"泄露 {key}"
        # 值层面也不得出现成本数字
        assert 100 not in product.values(), "保险原价不得以任何字段名出现"
    print("  exact allow-list ok")


def _test_min_price_is_present_and_correct(session, ctx):
    """§5.1 平台销售最低价由后端计算，前端只展示。底价 70 + 利润 10 = 80。"""
    configured = next(p for p in portal_products(session, ctx.agent_a)
                      if p["name"] == "已配置佣金的产品")
    assert configured["min_sale_price"] == 80.0, configured["min_sale_price"]
    print("  min sale price ok")


def _test_unconfigured_product_says_not_configured(session, ctx):
    p = next(x for x in portal_products(session, ctx.agent_a)
             if x["name"] == "未配置佣金的产品")
    assert p["my_commission_status"] == "未配置", p["my_commission_status"]
    configured = next(x for x in portal_products(session, ctx.agent_a)
                      if x["name"] == "已配置佣金的产品")
    assert configured["my_commission_status"] != "未配置"
    print("  unconfigured status ok")


def _test_agent_id_in_the_query_is_ignored(session, ctx):
    """§17.1 业务员不能通过传入 agent_id 查询他人数据。

    忽略而非报错：报错等于确认了对方存在，而且一个能被参数改写的身份边界，
    迟早会有人忘记校验。身份只从 JWT 来。
    """
    mine = portal_commission_summary(session, ctx.agent_a)
    spoofed = portal_commission_summary(session, ctx.agent_a, agent_id=ctx.agent_b.id)
    assert spoofed == mine, "传入他人 agent_id 必须被忽略"

    b_summary = portal_commission_summary(session, ctx.agent_b)
    assert b_summary["agent_id"] == ctx.agent_b.id
    assert mine["agent_id"] == ctx.agent_a.id
    print("  agent_id spoofing ignored ok")


if __name__ == "__main__":
    run()
