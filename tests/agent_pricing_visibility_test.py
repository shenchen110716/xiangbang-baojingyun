"""Salesperson product-centre pricing visibility (SYSTEM-DESIGN-V4.2 section 5.1).

A salesperson may see every on-sale product and the platform minimum sale
price, but never the cost basis behind it: insurer settlement price, original
premium, total rebate, or platform profit.  The stripping must happen in the
response schema, not in the frontend.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Fields SYSTEM-DESIGN-V4.2 section 5.1 forbids returning to a salesperson.
FORBIDDEN_FOR_SALESPERSON = {
    "insurance_base_price",      # 保险原价
    "price",                     # same cost basis, raw column name
    "policy_floor_price",        # 保司结算底价
    "insurer_settlement_price",  # 保司结算底价
    "profit_amount",             # 平台利润
    "platform_margin_amount",    # 平台毛利
    "total_commission_rate",     # 总返佣比例
    "commission_rate",           # same rate, raw column name
    "total_commission_amount",   # 总返佣金额
}


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-agent-pricing-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import InsurancePlan, User
        from backend.routers.plans import plans

        startup()
        with SessionLocal() as session:
            agent = User(username="vis_agent", password_hash=pwd.hash("sp12345"),
                         name="可见性业务员", role="salesperson")
            admin = session.scalar(__import__("sqlalchemy").select(User).where(User.role == "admin"))
            plan = InsurancePlan(name="可见性产品", insurer="可见性保司", price=100.0,
                                 commission_rate=0.3, profit_amount=10.0, status="active")
            session.add_all([agent, plan])
            session.commit()
            session.refresh(agent)

            rows = plans(agent, session)
            assert rows, "salesperson must see on-sale products"
            row = rows[0]

            leaked = FORBIDDEN_FOR_SALESPERSON & set(row)
            assert not leaked, f"salesperson must not see internal cost basis, leaked: {sorted(leaked)}"

            # The platform minimum sale price is explicitly allowed, and is the
            # number the portal renders.  floor(70) + profit(10) = 80.
            assert row.get("minimum_sale_price") == 80.0, \
                f"salesperson must still see minimum_sale_price, got {row.get('minimum_sale_price')}"

            # A salesperson sees the whole catalogue, including products with no
            # commission relation configured for them.
            assert row["name"] == "可见性产品"
            assert row["insurer"] == "可见性保司"

            # The admin keeps full visibility; this fix must not blind the platform.
            admin_row = plans(admin, session)[0]
            assert admin_row.get("insurer_settlement_price") == 70.0, \
                "admin must retain full cost visibility"
            assert admin_row.get("profit_amount") == 10.0

            _assert_operational_data_denied(agent, admin, session)

    print("agent pricing visibility: ok")


def _assert_operational_data_denied(agent, admin, session):
    """Section 5.1 also forbids returning enterprise sales/participation/operating
    data to a salesperson.  /dashboard and /screen/products aggregate exactly that
    across every enterprise, so a salesperson must not reach them at all."""
    from fastapi import HTTPException

    from backend.core.rbac import require_role

    gate = require_role("admin", "enterprise")
    for role_user, allowed in ((agent, False), (admin, True)):
        try:
            gate(role_user)
            reached = True
        except HTTPException as exc:
            reached = False
            assert exc.status_code == 403
        assert reached is allowed, \
            f"role={role_user.role} expected allowed={allowed}, got {reached}"

    # The endpoints must actually carry that gate, not merely be gateable.
    from backend.routers import dashboard as dashboard_router

    for path in ("/api/dashboard", "/api/screen/products"):
        route = next(r for r in dashboard_router.router.routes if r.path == path)
        assert route.dependencies, f"{path} must be role-gated against salespeople"


if __name__ == "__main__":
    run()
