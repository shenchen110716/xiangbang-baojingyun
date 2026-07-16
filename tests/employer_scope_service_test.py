from pathlib import Path
import sys

from fastapi import HTTPException
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import ActualEmployer, Enterprise, User, UserEmployerScope
from backend.services.employer_scopes import (
    allowed_employer_ids,
    assert_employer_access,
    grant_employer_scope,
    is_enterprise_owner,
    replace_primary_manager,
    revoke_employer_scope,
)


def _user(
    username: str,
    *,
    role: str,
    enterprise_id: int | None = None,
    enterprise_role: str | None = None,
    is_owner: bool = False,
) -> User:
    return User(
        username=username,
        password_hash="test",
        name=username,
        role=role,
        enterprise_id=enterprise_id,
        enterprise_role=enterprise_role,
        is_owner=is_owner,
    )


def _expect_http(status_code: int, operation) -> HTTPException:
    try:
        operation()
    except HTTPException as error:
        assert error.status_code == status_code, error.detail
        return error
    raise AssertionError(f"expected HTTP {status_code}")


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        enterprise_a = Enterprise(name="企业 A")
        enterprise_b = Enterprise(name="企业 B")
        session.add_all([enterprise_a, enterprise_b])
        session.flush()
        employer_a = ActualEmployer(enterprise_id=enterprise_a.id, name="项目 A")
        employer_b = ActualEmployer(enterprise_id=enterprise_a.id, name="项目 B")
        foreign_employer = ActualEmployer(enterprise_id=enterprise_b.id, name="外部项目")
        owner = _user(
            "owner",
            role="enterprise",
            enterprise_id=enterprise_a.id,
            enterprise_role="owner",
            is_owner=True,
        )
        legacy_owner = _user(
            "legacy_owner",
            role="enterprise",
            enterprise_id=enterprise_a.id,
            enterprise_role=None,
            is_owner=True,
        )
        manager_a = _user(
            "manager_a",
            role="enterprise",
            enterprise_id=enterprise_a.id,
            enterprise_role="project_manager",
        )
        manager_b = _user(
            "manager_b",
            role="enterprise",
            enterprise_id=enterprise_a.id,
            enterprise_role="project_manager",
        )
        manager_without_scope = _user(
            "manager_none",
            role="enterprise",
            enterprise_id=enterprise_a.id,
            enterprise_role="project_manager",
        )
        foreign_manager = _user(
            "foreign_manager",
            role="enterprise",
            enterprise_id=enterprise_b.id,
            enterprise_role="project_manager",
        )
        admin = _user("admin_test", role="admin")
        salesperson = _user("salesperson_test", role="salesperson")
        session.add_all(
            [
                employer_a,
                employer_b,
                foreign_employer,
                owner,
                legacy_owner,
                manager_a,
                manager_b,
                manager_without_scope,
                foreign_manager,
                admin,
                salesperson,
            ]
        )
        session.commit()

        assert is_enterprise_owner(owner) is True
        assert is_enterprise_owner(legacy_owner) is True
        assert is_enterprise_owner(manager_a) is False
        assert allowed_employer_ids(session, owner) is None
        assert allowed_employer_ids(session, admin) is None
        assert allowed_employer_ids(session, manager_without_scope) == set()
        _expect_http(403, lambda: allowed_employer_ids(session, salesperson))

        scope_a = grant_employer_scope(
            session, owner, manager_a.id, employer_a.id, "collaborator"
        )
        session.commit()
        assert allowed_employer_ids(session, manager_a) == {employer_a.id}
        assert assert_employer_access(session, manager_a, employer_a.id) is employer_a
        _expect_http(
            403, lambda: assert_employer_access(session, manager_a, employer_b.id)
        )
        _expect_http(
            403, lambda: assert_employer_access(session, manager_a, foreign_employer.id)
        )

        _expect_http(
            409,
            lambda: grant_employer_scope(
                session, owner, manager_a.id, employer_a.id, "collaborator"
            ),
        )
        _expect_http(
            403,
            lambda: grant_employer_scope(
                session, owner, foreign_manager.id, employer_a.id, "collaborator"
            ),
        )
        _expect_http(
            403,
            lambda: grant_employer_scope(
                session, manager_a, manager_b.id, employer_b.id, "collaborator"
            ),
        )
        _expect_http(
            400,
            lambda: grant_employer_scope(
                session, owner, manager_b.id, employer_b.id, "invalid"
            ),
        )

        primary_b = grant_employer_scope(
            session, owner, manager_b.id, employer_a.id, "primary"
        )
        session.commit()
        _expect_http(
            409,
            lambda: grant_employer_scope(
                session,
                owner,
                manager_without_scope.id,
                employer_a.id,
                "primary",
            ),
        )

        replacement = replace_primary_manager(
            session, owner, employer_a, manager_a
        )
        session.commit()
        active = session.scalars(
            select(UserEmployerScope).where(
                UserEmployerScope.actual_employer_id == employer_a.id,
                UserEmployerScope.status == "active",
                UserEmployerScope.revoked_at.is_(None),
            )
        ).all()
        assert active == [replacement]
        assert replacement.user_id == manager_a.id
        assert replacement.responsibility_type == "primary"
        assert session.get(UserEmployerScope, scope_a.id).status == "revoked"
        assert session.get(UserEmployerScope, primary_b.id).status == "revoked"

        revoked = revoke_employer_scope(session, owner, replacement.id)
        session.commit()
        assert revoked.status == "revoked"
        assert revoked.revoked_at is not None
        assert session.get(UserEmployerScope, revoked.id) is revoked
        assert allowed_employer_ids(session, manager_a) == set()
        _expect_http(
            403,
            lambda: revoke_employer_scope(session, manager_a, revoked.id),
        )

        admin_scope = grant_employer_scope(
            session, admin, foreign_manager.id, foreign_employer.id, "primary"
        )
        session.commit()
        assert admin_scope.enterprise_id == enterprise_b.id
        assert allowed_employer_ids(session, foreign_manager) == {foreign_employer.id}

    print("employer scope service test: ok")


if __name__ == "__main__":
    run()
