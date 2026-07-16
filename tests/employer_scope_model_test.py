from datetime import datetime, timezone
from pathlib import Path
import sys

from sqlalchemy import create_engine, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.core.migrations import run_sqlite_bridge_migrations
from backend.core.seed import seed_default_accounts
from backend.models import ActualEmployer, Enterprise, User, UserEmployerScope


def _user(username: str, enterprise_id: int, *, enterprise_role: str) -> User:
    return User(
        username=username,
        password_hash="test",
        name=username,
        role="enterprise",
        enterprise_id=enterprise_id,
        enterprise_role=enterprise_role,
        is_owner=enterprise_role == "owner",
    )


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        enterprise = Enterprise(name="范围测试企业")
        session.add(enterprise)
        session.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="项目 A")
        owner = _user("owner", enterprise.id, enterprise_role="owner")
        manager_a = _user("manager_a", enterprise.id, enterprise_role="project_manager")
        manager_b = _user("manager_b", enterprise.id, enterprise_role="project_manager")
        session.add_all([employer, owner, manager_a, manager_b])
        session.flush()

        assigned_at = datetime.now(timezone.utc)
        first = UserEmployerScope(
            user_id=manager_a.id,
            enterprise_id=enterprise.id,
            actual_employer_id=employer.id,
            responsibility_type="primary",
            granted_by=owner.id,
            assigned_at=assigned_at,
        )
        session.add(first)
        session.commit()

        assert manager_a.enterprise_role == "project_manager"
        active = session.scalars(
            select(UserEmployerScope).where(
                UserEmployerScope.actual_employer_id == employer.id,
                UserEmployerScope.status == "active",
                UserEmployerScope.revoked_at.is_(None),
            )
        ).all()
        assert active == [first]

        session.add(
            UserEmployerScope(
                user_id=manager_b.id,
                enterprise_id=enterprise.id,
                actual_employer_id=employer.id,
                responsibility_type="primary",
                granted_by=owner.id,
                assigned_at=assigned_at,
            )
        )
        try:
            session.commit()
        except IntegrityError:
            session.rollback()
        else:
            raise AssertionError("a second active primary manager must be rejected")

    seed_engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(seed_engine)
    with Session(seed_engine) as session:
        seed_default_accounts(session)
        seeded_owner = session.scalar(select(User).where(User.username == "enterprise"))
        assert seeded_owner is not None
        assert seeded_owner.enterprise_role == "owner"

    legacy_engine = create_engine("sqlite:///:memory:")
    with Session(legacy_engine) as session:
        connection = session.connection()
        connection.exec_driver_sql(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, role VARCHAR(40), "
            "is_owner BOOLEAN, enterprise_id INTEGER, phone VARCHAR(30), status VARCHAR(30))"
        )
        connection.exec_driver_sql(
            "CREATE TABLE enterprises (id INTEGER PRIMARY KEY, agent_id INTEGER, "
            "usage_fee_daily FLOAT, alert_days INTEGER)"
        )
        connection.exec_driver_sql("CREATE TABLE actual_employers (id INTEGER PRIMARY KEY)")
        connection.exec_driver_sql(
            "CREATE TABLE agent_commissions (id INTEGER PRIMARY KEY, mode VARCHAR(20), "
            "markup_amount FLOAT, sale_price FLOAT, plan_id INTEGER)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE insurance_plans (id INTEGER PRIMARY KEY, billing_mode VARCHAR(20), "
            "effective_mode VARCHAR(20), insurer_email VARCHAR(160), profit_amount FLOAT, "
            "price FLOAT, commission_rate FLOAT)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE insured_people (id INTEGER PRIMARY KEY, id_number VARCHAR(40), "
            "position_id INTEGER)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE work_positions (id INTEGER PRIMARY KEY, actual_employer_id INTEGER, "
            "created_by INTEGER)"
        )
        connection.exec_driver_sql("CREATE TABLE claims (id INTEGER PRIMARY KEY)")
        connection.exec_driver_sql(
            "CREATE TABLE claim_documents (id INTEGER PRIMARY KEY, review_note TEXT)"
        )
        connection.exec_driver_sql(
            "CREATE TABLE policies (id INTEGER PRIMARY KEY, document_url TEXT, "
            "document_name VARCHAR(200))"
        )
        connection.exec_driver_sql(
            "CREATE TABLE ledger_entries (id INTEGER PRIMARY KEY, account_id INTEGER)"
        )
        connection.exec_driver_sql(
            "INSERT INTO users (id, role, is_owner, enterprise_id) VALUES "
            "(1, 'enterprise', 1, 1), (2, 'enterprise', 0, 1)"
        )
        run_sqlite_bridge_migrations(session, "sqlite:///:memory:")
        columns = {
            row[1] for row in connection.exec_driver_sql("PRAGMA table_info(users)")
        }
        assert "enterprise_role" in columns
        roles = connection.exec_driver_sql(
            "SELECT id, enterprise_role FROM users ORDER BY id"
        ).all()
        assert roles == [(1, "owner"), (2, "project_manager")]
        assert connection.exec_driver_sql(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='user_employer_scopes'"
        ).first()

    print("employer scope model test: ok")


if __name__ == "__main__":
    run()
