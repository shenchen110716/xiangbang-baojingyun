"""GET /plan-tiers must apply the same cost-basis stripping and visibility
scope as GET /plans (services/pricing.py:strip_internal_pricing). Before this
fix, plan_tiers() returned raw serialize(tier) with no stripping and only
filtered by AgentCommission (ignoring WorkPosition-only visibility), so an
enterprise/miniprogram user could read the platform's per-occupation-class
cost basis and lost visibility into tiers for plans reachable only through a
position assignment.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

FORBIDDEN_FOR_ENTERPRISE = {
    "insurance_base_price", "price", "policy_floor_price",
    "insurer_settlement_price", "profit_amount", "platform_margin_amount",
    "total_commission_rate", "commission_rate", "total_commission_amount",
}


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-plan-tiers-pricing-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import (
            AgentCommission, Enterprise, InsurancePlan, PlanTier, User, WorkPosition,
        )
        from backend.routers.plans import plan_tiers

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            plan_commission = InsurancePlan(name="佣金关联产品", insurer="A保司", price=100.0,
                                             commission_rate=0.3, profit_amount=10.0, status="active")
            plan_position = InsurancePlan(name="岗位关联产品", insurer="B保司", price=200.0,
                                           commission_rate=0.2, profit_amount=20.0, status="active")
            plan_unreachable = InsurancePlan(name="无关产品", insurer="C保司", price=300.0,
                                              commission_rate=0.1, profit_amount=5.0, status="active")
            ent_a = Enterprise(name="甲企业")
            ent_b = Enterprise(name="乙企业（无权限）")
            session.add_all([plan_commission, plan_position, plan_unreachable, ent_a, ent_b])
            session.commit()

            tier_commission = PlanTier(plan_id=plan_commission.id, occupation_class="1类", price=90.0, status="active")
            tier_position = PlanTier(plan_id=plan_position.id, occupation_class="2类", price=150.0, status="active")
            tier_unreachable = PlanTier(plan_id=plan_unreachable.id, occupation_class="3类", price=250.0, status="active")
            commission = AgentCommission(agent_id=admin.id, enterprise_id=ent_a.id, plan_id=plan_commission.id,
                                          mode="rebate", rate=0.1, status="active")
            position = WorkPosition(enterprise_id=ent_a.id, name="岗位A", plan_id=plan_position.id, status="active")
            enterprise_user = User(username="tiers_ent", password_hash=pwd.hash("ent12345"),
                                    name="甲企业管理员", role="enterprise", enterprise_id=ent_a.id)
            outsider_user = User(username="tiers_out", password_hash=pwd.hash("out12345"),
                                  name="乙企业管理员", role="enterprise", enterprise_id=ent_b.id)
            session.add_all([tier_commission, tier_position, tier_unreachable, commission, position,
                              enterprise_user, outsider_user])
            session.commit()
            session.refresh(enterprise_user)
            session.refresh(outsider_user)

            # 1. Enterprise sees tiers reachable via AgentCommission AND via
            #    WorkPosition (visibility gap), never the unrelated plan's tier.
            rows = plan_tiers(None, enterprise_user, session)
            seen_plan_ids = {r["plan_id"] for r in rows}
            assert plan_commission.id in seen_plan_ids, "must see tier reachable via commission relation"
            assert plan_position.id in seen_plan_ids, "must see tier reachable via position assignment (previously missing)"
            assert plan_unreachable.id not in seen_plan_ids, "must not see tier for an unrelated plan"

            # 2. No internal cost-basis field leaks to the enterprise role.
            for row in rows:
                leaked = FORBIDDEN_FOR_ENTERPRISE & set(row)
                assert not leaked, f"enterprise must not see internal pricing, leaked: {sorted(leaked)}"
                assert "sale_price" in row, "enterprise must see the actual chargeable sale price"

            # 3. sale_price is computed off the tier's own price (not the plan's),
            #    confirming pricing_snapshot was given tier.price as base.
            row_commission = next(r for r in rows if r["plan_id"] == plan_commission.id)
            # floor = 90 - 90*0.3 = 63; minimum = floor + profit(10) = 73;
            # rebate mode -> sale == minimum.
            assert row_commission["sale_price"] == 73.0, row_commission["sale_price"]

            # 4. An enterprise with neither a commission relation nor a position
            #    assignment for these plans sees nothing.
            outsider_rows = plan_tiers(None, outsider_user, session)
            assert outsider_rows == [], f"outsider enterprise must see no tiers, got {outsider_rows}"

            # 5. Admin keeps full cost visibility (no regression).
            admin_rows = plan_tiers(None, admin, session)
            admin_ids = {r["plan_id"] for r in admin_rows}
            assert {plan_commission.id, plan_position.id, plan_unreachable.id} <= admin_ids
            admin_row = next(r for r in admin_rows if r["plan_id"] == plan_commission.id)
            assert admin_row.get("price") == 90.0
            assert admin_row.get("insurer_settlement_price") == 63.0

    print("plan-tiers pricing visibility: ok")


if __name__ == "__main__":
    run()
