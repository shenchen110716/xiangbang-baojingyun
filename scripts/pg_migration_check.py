#!/usr/bin/env python3
"""Run the Alembic migration chain against a real PostgreSQL, on a throwaway
Neon branch cut from production.

Why this exists: v4.2 Phase 2 shipped a migration that passed every SQLite test
and rendered clean offline SQL, then failed in production because PostgreSQL
rejects an integer default on a boolean column. Offline SQL proves syntax, not
type compatibility, and SQLite accepts things PostgreSQL does not. The only
honest check is executing the migration on the real engine.

The branch is copy-on-write from production: it sees production's schema and
data without touching them, so this also exercises the upgrade-an-existing-
database path rather than only an empty one. It is deleted on every exit path,
including failure and Ctrl-C.

Usage:
    python3 scripts/pg_migration_check.py            # verify head applies
    python3 scripts/pg_migration_check.py --keep     # leave the branch for debugging

Credentials: reads a Neon API key from $NEON_API_KEY or ~/.neon_api_key.
The key and the branch connection string are never printed.
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API = "https://console.neon.tech/api/v2"
BRANCH_PREFIX = "migration-check-"


def _api_key() -> str:
    key = os.getenv("NEON_API_KEY", "").strip()
    if key:
        return key
    path = Path.home() / ".neon_api_key"
    if path.exists():
        return path.read_text().strip()
    sys.exit(
        "缺少 Neon API key。请设置 NEON_API_KEY，或写入 ~/.neon_api_key：\n"
        "  printf '%s' 'napi_xxx' > ~/.neon_api_key && chmod 600 ~/.neon_api_key"
    )


def _call(method: str, path: str, key: str, body: dict | None = None) -> dict:
    # curl rather than urllib: this machine's Python has no usable CA bundle
    # (urllib raises CERTIFICATE_VERIFY_FAILED against the Neon API), and adding
    # certifi just for a dev script is not worth a new dependency. The key goes
    # in via a header argument, never into the process list of another program.
    command = [
        "curl", "-sS", "--max-time", "60", "-X", method,
        "-H", f"Authorization: Bearer {key}",
        "-H", "Content-Type: application/json",
        "-H", "Accept: application/json",
        "-w", "\n%{http_code}",
        f"{API}{path}",
    ]
    if body is not None:
        command += ["-d", json.dumps(body)]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"Neon API {method} {path} 网络失败：{result.stderr.strip()[:200]}")
    raw, _, status = result.stdout.rpartition("\n")
    if not status.strip().startswith("2"):
        raise SystemExit(f"Neon API {method} {path} 失败：{status.strip()} {raw[:300]}")
    return json.loads(raw) if raw.strip() else {}


def _pick_project(key: str, wanted: str | None) -> dict:
    projects = _call("GET", "/projects", key).get("projects", [])
    if not projects:
        raise SystemExit("该 Neon 账号下没有项目")
    if wanted:
        for project in projects:
            if wanted in (project["id"], project["name"]):
                return project
        raise SystemExit(f"未找到项目 {wanted}；可用：{[p['name'] for p in projects]}")
    if len(projects) > 1:
        raise SystemExit(
            f"账号下有多个项目，请用 --project 指定：{[p['name'] for p in projects]}")
    return projects[0]


def _default_branch(key: str, project_id: str) -> dict:
    branches = _call("GET", f"/projects/{project_id}/branches", key).get("branches", [])
    for branch in branches:
        if branch.get("default") or branch.get("primary"):
            return branch
    raise SystemExit("未找到生产主分支")


def _wait_ready(key: str, project_id: str, branch_id: str) -> None:
    for _ in range(60):
        branch = _call("GET", f"/projects/{project_id}/branches/{branch_id}", key)["branch"]
        if branch.get("current_state") == "ready":
            return
        time.sleep(2)
    raise SystemExit("临时分支未在预期时间内就绪")


def _connection_uri(key: str, project_id: str, branch_id: str) -> str:
    # "neondb"/"neondb_owner" are only the defaults Neon proposes on brand-new
    # projects; a project can be (and here, is) set up with a different
    # database/role name, so look up what the branch actually has instead of
    # hardcoding the placeholder names.
    databases = _call("GET", f"/projects/{project_id}/branches/{branch_id}/databases", key).get("databases", [])
    if not databases:
        raise SystemExit("临时分支上没有数据库")
    database_name = databases[0]["name"]
    role_name = databases[0]["owner_name"]
    data = _call("GET", f"/projects/{project_id}/connection_uri"
                        f"?branch_id={branch_id}&database_name={database_name}&role_name={role_name}",
                 key)
    uri = data.get("uri")
    if not uri:
        raise SystemExit("无法获取临时分支连接串")
    return uri


def _alembic(uri: str, *args: str) -> subprocess.CompletedProcess:
    env = os.environ.copy()
    env["DATABASE_URL"] = uri
    # The migration chain imports backend.core.config, whose production check
    # would abort on a non-production-looking environment.
    env["ENVIRONMENT"] = "development"
    env.setdefault("ID_ENCRYPTION_KEY", "migration-check-only")
    return subprocess.run(
        [sys.executable, "-m", "alembic", "-c", "alembic.ini", *args],
        cwd=ROOT, env=env, capture_output=True, text=True)


def _verify_schema(uri: str) -> list[str]:
    """Assert what the migration claims to have built actually exists, with the
    right types. A migration can 'succeed' and still produce a wrong column."""
    import psycopg

    problems: list[str] = []
    with psycopg.connect(uri, connect_timeout=30) as conn:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public'
            """)
            tables = {r[0] for r in cur.fetchall()}
            expected = {
                "employment_feedback_batches", "employment_facts",
                "employment_fact_matches", "integration_api_keys",
                "integration_nonces",
            }
            missing = expected - tables
            if missing:
                problems.append(f"缺少表：{sorted(missing)}")

            # The exact defect that reached production: a boolean column whose
            # default is not a boolean.
            cur.execute("""
                SELECT table_name, column_name, data_type, column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND data_type = 'boolean'
                   AND column_default IS NOT NULL
            """)
            for table, column, _type, default in cur.fetchall():
                if default.strip().lower() not in ("true", "false"):
                    problems.append(
                        f"{table}.{column} 是布尔列但默认值为 {default!r}（PostgreSQL 会拒绝）")

            cur.execute("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
            """)
            indexes = {r[0] for r in cur.fetchall()}
            for name in ("ux_fact_source_event", "ix_fact_scope_window",
                         "ux_batch_confirmed_file", "ux_nonce_per_key"):
                if name not in indexes:
                    problems.append(f"缺少索引：{name}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", help="Neon 项目 id 或名称")
    parser.add_argument("--keep", action="store_true", help="失败时保留分支以便排查")
    args = parser.parse_args()

    key = _api_key()
    project = _pick_project(key, args.project)
    project_id = project["id"]
    parent = _default_branch(key, project_id)
    print(f"项目：{project['name']}  生产主分支：{parent['name']}")

    branch_name = f"{BRANCH_PREFIX}{int(time.time())}"
    created = _call("POST", f"/projects/{project_id}/branches", key, {
        "branch": {"name": branch_name, "parent_id": parent["id"]},
        "endpoints": [{"type": "read_write"}],
    })
    branch_id = created["branch"]["id"]
    print(f"已从生产快照创建临时分支：{branch_name}")

    failed = False
    try:
        _wait_ready(key, project_id, branch_id)
        uri = _connection_uri(key, project_id, branch_id)

        before = _alembic(uri, "current")
        print(f"分支当前版本：{(before.stdout or before.stderr).strip().splitlines()[-1:]}")

        print("执行 alembic upgrade head ...")
        result = _alembic(uri, "upgrade", "head")
        if result.returncode != 0:
            failed = True
            print("\n=== 迁移失败 ===")
            tail = (result.stderr or result.stdout).strip().splitlines()
            print("\n".join(tail[-25:]))
        else:
            for line in (result.stderr or "").splitlines():
                if "Running upgrade" in line:
                    print(f"  {line.split(']')[-1].strip()}")
            problems = _verify_schema(uri)
            if problems:
                failed = True
                print("\n=== 结构校验失败 ===")
                for p in problems:
                    print(f"  - {p}")
            else:
                print("结构校验通过：表、索引与布尔默认值均正确")
    finally:
        if failed and args.keep:
            print(f"保留分支 {branch_name}（--keep）；排查后请手动删除")
        else:
            _call("DELETE", f"/projects/{project_id}/branches/{branch_id}", key)
            print(f"已删除临时分支：{branch_name}")

    if failed:
        print("\nPostgreSQL 迁移门槛：未通过")
        return 1
    print("\nPostgreSQL 迁移门槛：通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
