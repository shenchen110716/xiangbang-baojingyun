"""Regression: an enterprise admin created through the onboarding flow must be
a real enterprise owner.

The bug: POST /enterprises/{id}/admins created the enterprise's admin account
with neither is_owner=True nor enterprise_role='owner'. The authoritative check
is_enterprise_owner() then returns False for that account, which cascades into:
  - operator management denied (仅单位主管可管理操作员 -> 403)
  - every employer-scoped read (positions, insured people, employment facts,
    timeliness) filtered as if the owner were a project manager with no scopes

This is the primary onboarding path (web EnterprisesPanel calls it), so every
enterprise created after Phase 1 shipped had a non-functional "owner".

Direct-call test (no HTTP): the defect is in the row the function writes, not
in a dependency, so a plain call reproduces it.
"""
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-owner-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"

        from sqlalchemy import select

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.routers.enterprises import add_enterprise, add_enterprise_admin
        from backend.schemas import AgentIn, EnterpriseIn
        from backend.services.employer_scopes import is_enterprise_owner

        startup()
        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.role == "admin"))

            created = add_enterprise(
                EnterpriseIn(name="新客户单位", contact="王总", phone="13800000000"),
                admin, session)
            enterprise_id = created["id"]

            # The onboarding action under test: platform admin creates the
            # enterprise's own admin/owner account.
            result = add_enterprise_admin(
                enterprise_id,
                AgentIn(username="newco_owner", password="pass1234",
                        name="王总", phone="13800000000"),
                admin, session)

            owner = session.get(User, result["id"])

            assert owner.is_owner is True, (
                "onboarding-created enterprise admin must have is_owner=True; "
                f"got is_owner={owner.is_owner}")
            assert owner.enterprise_role == "owner", (
                "onboarding-created enterprise admin must have "
                f"enterprise_role='owner'; got {owner.enterprise_role!r}")
            assert is_enterprise_owner(owner) is True, (
                "the authoritative is_enterprise_owner() must recognise the "
                "onboarding-created account as an owner — otherwise operator "
                "management and every employer-scoped read is denied to them")

            # A second admin for the same enterprise must NOT become a competing
            # owner — one active primary owner per enterprise. The second is a
            # regular enterprise operator (project manager).
            second = add_enterprise_admin(
                enterprise_id,
                AgentIn(username="newco_admin2", password="pass1234",
                        name="李经理", phone="13900000000"),
                admin, session)
            second_user = session.get(User, second["id"])
            assert second_user.is_owner is False, (
                "only the first admin becomes the owner; a later one must not "
                f"also be owner; got is_owner={second_user.is_owner}")

    print("enterprise admin owner test: PASS")


if __name__ == "__main__":
    run()
