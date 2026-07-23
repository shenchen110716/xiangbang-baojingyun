"""SQLite verification for migration a1b2c3d4e5f6 (backfill enterprise owner).

Local SQLite cannot replay the full Alembic chain (earlier revisions ALTER
constraints, which SQLite rejects — the reason local runs the runtime bridge,
not Alembic). So the schema is built by the app (create_all + bridge), Alembic
is stamped at the previous head, and `upgrade head` runs ONLY this migration,
which is pure SELECT + UPDATE and therefore engine-agnostic.

This proves the heal logic on SQLite. The mandatory PostgreSQL gate
(scripts/pg_migration_check.py) is separate and MUST pass before merge.
"""
import os
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREV_HEAD = "27951ec2f8ee"
# Pinned to this one migration, not "head": this test isolates its SELECT+UPDATE
# backfill logic, which is engine-agnostic. Later migrations added downstream
# (e.g. new ADD COLUMNs) are not idempotent against create_all() and would
# break "upgrade head" here even though they have nothing to do with this test.
TARGET = "a1b2c3d4e5f6"


def _alembic(url, *args):
    env = dict(os.environ, DATABASE_URL=url)
    r = subprocess.run([sys.executable, "-m", "alembic", *args],
                       cwd=ROOT, env=env, capture_output=True, text=True)
    assert r.returncode == 0, f"alembic {args} failed:\n{r.stdout}\n{r.stderr}"


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-backfill-") as folder:
        url = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["DATABASE_URL"] = url
        os.environ.setdefault("ADMIN_PASSWORD", "admin123")
        os.environ.setdefault("ENTERPRISE_PASSWORD", "enterprise123")
        sys.path.insert(0, str(ROOT))

        import sqlalchemy as sa

        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User

        startup()

        with SessionLocal() as s:
            def mk_ent(name):
                e = Enterprise(name=name, created_at=datetime(2026, 1, 1))
                s.add(e); s.flush()
                return e.id

            def mk_user(uname, ent, is_owner, role):
                u = User(username=uname, password_hash="x", name=uname, role="enterprise",
                         enterprise_id=ent, is_owner=is_owner, enterprise_role=role,
                         active=True, status="active", created_at=datetime(2026, 1, 1))
                s.add(u); s.flush()
                return u.id

            a1 = mk_user("a_admin", mk_ent("A-broken"), False, None)
            b = mk_ent("B-has-owner")
            b1 = mk_user("b_owner", b, True, "owner")
            b2 = mk_user("b_admin2", b, False, "project_manager")
            cc = mk_ent("C-two-broken")
            c1 = mk_user("c_first", cc, False, None)
            c2 = mk_user("c_second", cc, False, None)
            s.commit()

        _alembic(url, "stamp", PREV_HEAD)
        _alembic(url, "upgrade", TARGET)

        eng = sa.create_engine(url)
        with eng.connect() as c:
            def owner(uid):
                row = c.execute(sa.text("SELECT is_owner, enterprise_role FROM users WHERE id=:i"), {"i": uid}).one()
                return bool(row[0]), row[1]

            assert owner(a1) == (True, "owner"), "orphaned admin must be healed to owner"
            assert owner(b1) == (True, "owner"), "existing owner must be untouched"
            assert owner(b2)[0] is False, "a second admin must not become a competing owner"
            assert owner(c1) == (True, "owner"), "lowest-id admin heals when enterprise has none"
            assert owner(c2)[0] is False, "only one admin per enterprise is promoted"

        # Idempotent: re-applying must not promote the already-non-owner second admin.
        _alembic(url, "downgrade", PREV_HEAD)
        _alembic(url, "upgrade", TARGET)
        with eng.connect() as c:
            still_non_owner = not bool(c.execute(sa.text("SELECT is_owner FROM users WHERE id=:i"), {"i": c2}).scalar())
        assert still_non_owner, "re-apply must stay idempotent"

    print("enterprise owner backfill test: PASS")


if __name__ == "__main__":
    run()
