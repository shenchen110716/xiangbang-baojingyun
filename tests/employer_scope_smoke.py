"""HTTP security smoke test for project-manager employer scopes.

The test intentionally exercises real FastAPI dependencies over HTTP.  It is
the executable contract for v4.2 Phase 1: enterprise owners may manage
historical employer scopes, while project managers receive no data outside
their active actual-employer assignments.
"""

import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run() -> None:
    port = _free_port()
    base = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener()

    def call(method: str, path: str, token: str | None = None, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            f"{base}{path}",
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers,
            method=method,
        )
        try:
            with opener.open(request, timeout=10) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if raw else None

    def ok(method: str, path: str, token: str | None = None, body=None):
        status, payload = call(method, path, token, body)
        assert status == 200, f"{method} {path} failed: {status} {payload}"
        return payload

    def login(username: str, password: str, portal: str) -> str:
        payload = ok(
            "POST",
            "/api/auth/login",
            body={"username": username, "password": password, "portal": portal},
        )
        return payload["access_token"]

    def add_operator(token: str, username: str, name: str, enterprise_id=None):
        body = {
            "username": username,
            "password": "pass1234",
            "name": name,
        }
        if enterprise_id is not None:
            body["enterprise_id"] = enterprise_id
        return ok("POST", "/api/operators", token, body)

    def add_employer(token: str, name: str):
        return ok("POST", "/api/actual-employers", token, {"name": name})

    def add_position(token: str, employer: dict):
        return call(
            "POST",
            "/api/positions",
            token,
            {
                "actual_employer_id": employer["id"],
                "actual_employer": employer["name"],
                "name": f"{employer['name']}岗位",
                "occupation_class": "1-3类",
            },
        )

    with tempfile.TemporaryDirectory(prefix="xbb-employer-scope-") as folder:
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "backend.app:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            cwd=ROOT,
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            for _ in range(50):
                try:
                    if call("GET", "/api/health")[0] == 200:
                        break
                except (urllib.error.URLError, ConnectionRefusedError):
                    pass
                time.sleep(0.2)
            else:
                raise RuntimeError("server did not come up in time")

            admin = login("admin", "admin123", "admin")
            owner = login("enterprise", "enterprise123", "enterprise")
            owner_profile = ok("GET", "/api/auth/me", owner)
            enterprise_id = owner_profile["enterprise_id"]
            assert owner_profile["is_owner"] is True

            manager = add_operator(owner, "scope_manager", "项目负责人甲")
            manager_two = add_operator(owner, "scope_manager_two", "项目负责人乙")
            employer_a = add_employer(owner, "项目 A")
            employer_b = add_employer(owner, "项目 B")

            patched = ok(
                "PATCH",
                f"/api/operators/{manager['id']}",
                owner,
                {"enterprise_role": "project_manager"},
            )
            assert patched.get("enterprise_role") == "project_manager"
            assert call(
                "PATCH",
                f"/api/operators/{manager_two['id']}",
                owner,
                {"enterprise_role": "owner"},
            )[0] == 403

            scope = ok(
                "POST",
                "/api/employer-scopes",
                owner,
                {
                    "user_id": manager["id"],
                    "actual_employer_id": employer_a["id"],
                    "responsibility_type": "primary",
                },
            )
            expected_fields = {
                "id",
                "user_id",
                "user_name",
                "enterprise_id",
                "actual_employer_id",
                "actual_employer_name",
                "responsibility_type",
                "assigned_at",
                "revoked_at",
                "status",
            }
            assert expected_fields <= scope.keys()

            manager_token = login("scope_manager", "pass1234", "enterprise")
            assert call(
                "PATCH",
                f"/api/operators/{manager['id']}",
                manager_token,
                {"enterprise_role": "owner"},
            )[0] == 403
            assert call("GET", "/api/employer-scopes", manager_token)[0] == 403
            visible = ok("GET", "/api/actual-employers", manager_token)
            assert {row["id"] for row in visible} == {employer_a["id"]}
            assert add_position(manager_token, employer_a)[0] == 200
            assert add_position(manager_token, employer_b)[0] == 403
            assert call(
                "POST",
                "/api/employer-scopes",
                manager_token,
                {
                    "user_id": manager["id"],
                    "actual_employer_id": employer_b["id"],
                    "responsibility_type": "collaborator",
                },
            )[0] == 403

            second_primary_status, _ = call(
                "POST",
                "/api/employer-scopes",
                owner,
                {
                    "user_id": manager_two["id"],
                    "actual_employer_id": employer_a["id"],
                    "responsibility_type": "primary",
                },
            )
            assert second_primary_status == 409

            enterprise_b = ok("POST", "/api/enterprises", admin, {"name": "租户 B"})
            foreign_manager = add_operator(
                admin, "scope_foreign_manager", "其他租户负责人", enterprise_b["id"]
            )
            foreign_status, _ = call(
                "POST",
                "/api/employer-scopes",
                owner,
                {
                    "user_id": foreign_manager["id"],
                    "actual_employer_id": employer_a["id"],
                    "responsibility_type": "collaborator",
                },
            )
            assert foreign_status == 403
            promoted = ok(
                "PATCH",
                f"/api/operators/{foreign_manager['id']}",
                admin,
                {"enterprise_role": "owner"},
            )
            assert promoted["enterprise_role"] == "owner"
            assert promoted["is_owner"] is True
            restored = ok(
                "PATCH",
                f"/api/operators/{foreign_manager['id']}",
                admin,
                {"enterprise_role": "project_manager"},
            )
            assert restored["enterprise_role"] == "project_manager"
            assert restored["is_owner"] is False

            replacement = ok(
                "POST",
                f"/api/actual-employers/{employer_a['id']}/primary-manager",
                owner,
                {"user_id": manager_two["id"]},
            )
            assert replacement["user_id"] == manager_two["id"]
            scopes = ok("GET", "/api/employer-scopes", owner)
            admin_scopes = ok(
                "GET", f"/api/employer-scopes?enterprise_id={enterprise_id}", admin
            )
            assert {row["id"] for row in admin_scopes} == {row["id"] for row in scopes}
            active_primary = [
                row
                for row in scopes
                if row["actual_employer_id"] == employer_a["id"]
                and row["responsibility_type"] == "primary"
                and row["status"] == "active"
            ]
            assert len(active_primary) == 1
            assert active_primary[0]["user_id"] == manager_two["id"]
            assert any(row["id"] == scope["id"] and row["status"] == "revoked" for row in scopes)

            manager_two_token = login("scope_manager_two", "pass1234", "enterprise")
            assert {
                row["id"] for row in ok("GET", "/api/actual-employers", manager_two_token)
            } == {employer_a["id"]}
            ok("DELETE", f"/api/employer-scopes/{active_primary[0]['id']}", owner)
            assert ok("GET", "/api/actual-employers", manager_two_token) == []
            assert ok("GET", "/api/positions", manager_two_token) == []

            audit_rows = ok("GET", "/api/audit-logs?limit=100", owner)
            actions = {
                row["action"]
                for row in audit_rows
                if row["object_type"] == "user_employer_scope"
            }
            assert {"create", "replace_primary", "revoke"} <= actions
            assert all(
                row["enterprise_id"] == enterprise_id
                for row in ok("GET", "/api/employer-scopes", owner)
            )

            print("employer scope smoke: ok")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()


if __name__ == "__main__":
    run()
