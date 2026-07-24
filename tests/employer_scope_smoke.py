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

    def call_bytes(method: str, path: str, token: str | None = None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        request = urllib.request.Request(
            f"{base}{path}", headers=headers, method=method
        )
        try:
            with opener.open(request, timeout=10) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

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

    def add_approved_position(
        admin_token: str, enterprise_id: int, employer: dict, plan_id: int
    ):
        position = ok(
            "POST",
            "/api/positions",
            admin_token,
            {
                "enterprise_id": enterprise_id,
                "actual_employer_id": employer["id"],
                "actual_employer": employer["name"],
                "name": f"{employer['name']}已审核岗位",
                "occupation_class": "1-3类",
                "plan_id": plan_id,
            },
        )
        ok(
            "POST",
            f"/api/positions/{position['id']}/videos",
            admin_token,
            {"name": "范围测试视频", "url": "https://example.com/scope.mp4"},
        )
        return ok(
            "PATCH",
            f"/api/positions/{position['id']}/review",
            admin_token,
            {
                "status": "approved",
                "occupation_class": "1-3类",
                "plan_id": plan_id,
            },
        )

    def add_active_person(
        admin_token: str,
        enterprise_id: int,
        position_id: int,
        name: str,
        id_number: str,
    ):
        person = ok(
            "POST",
            "/api/insured",
            admin_token,
            {
                "enterprise_id": enterprise_id,
                "name": name,
                "id_number": id_number,
                "position_id": position_id,
            },
        )
        return ok(
            "PATCH",
            f"/api/insured/{person['id']}/status?status=active",
            admin_token,
        )

    def add_claim(token: str, enterprise_id: int, person_id: int, label: str):
        return call(
            "POST",
            "/api/claims",
            token,
            {
                "enterprise_id": enterprise_id,
                "person_id": person_id,
                "description": f"{label}事故",
                "medical_cost": 100,
                "amount": 100,
                "accident_at": "2026-07-16 09:00",
                "accident_place": f"{label}现场",
            },
        )

    def upload_import_csv(
        token: str,
        enterprise_id: int,
        position_id: int,
        name: str,
        id_number: str,
    ):
        boundary = "----xbb-employer-scope-import"
        fields = {
            "kind": "enrollment",
            "enterprise_id": str(enterprise_id),
            "position_id": str(position_id),
        }
        chunks: list[bytes] = []
        for field_name, value in fields.items():
            chunks.append(
                (
                    f"--{boundary}\r\n"
                    f"Content-Disposition: form-data; name=\"{field_name}\"\r\n\r\n"
                    f"{value}\r\n"
                ).encode()
            )
        csv_content = f"姓名,身份证号,手机号\n{name},{id_number},13800000000\n".encode(
            "utf-8-sig"
        )
        chunks.append(
            (
                f"--{boundary}\r\n"
                'Content-Disposition: form-data; name="file"; filename="scope.csv"\r\n'
                "Content-Type: text/csv\r\n\r\n"
            ).encode()
            + csv_content
            + b"\r\n"
        )
        chunks.append(f"--{boundary}--\r\n".encode())
        request = urllib.request.Request(
            f"{base}/api/insured/import-file",
            data=b"".join(chunks),
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
            },
        )
        try:
            with opener.open(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if raw else None

    with tempfile.TemporaryDirectory(prefix="xbb-employer-scope-") as folder:
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        # 捕获子进程输出到文件而不是 DEVNULL：之前静默丢弃，一旦服务器没起来
        # （端口冲突、启动异常等）只会看到"server did not come up in time"，
        # 看不出真正原因；同时轮询时先查进程是否已经退出，提前失败，不用干等满整个超时。
        log_path = Path(folder) / "server.log"
        log_file = open(log_path, "wb")
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
            stdout=log_file,
            stderr=subprocess.STDOUT,
        )
        log_file.close()
        try:
            for _ in range(75):
                if process.poll() is not None:
                    raise RuntimeError(f"server exited early (code {process.returncode}):\n{log_path.read_text(errors='replace')}")
                try:
                    if call("GET", "/api/health")[0] == 200:
                        break
                except (urllib.error.URLError, ConnectionRefusedError):
                    pass
                time.sleep(0.2)
            else:
                raise RuntimeError(f"server did not come up in time; log:\n{log_path.read_text(errors='replace')}")

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
            manager_profile = ok("GET", "/api/auth/me", manager_token)
            assert manager_profile["enterprise_role"] == "project_manager"
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

            ok(
                "POST",
                f"/api/enterprises/{enterprise_id}/recharge",
                admin,
                {"account": "usage", "amount": 100},
            )
            plan = ok(
                "POST",
                "/api/plans",
                admin,
                {
                    "insurer": "范围测试保司",
                    "insurer_email": "scope@example.com",
                    "name": "范围测试产品",
                    "price": 30,
                    "commission_rate": 0.1,
                    "profit_amount": 3,
                },
            )
            position_a = add_approved_position(
                admin, enterprise_id, employer_a, plan["id"]
            )
            position_b = add_approved_position(
                admin, enterprise_id, employer_b, plan["id"]
            )
            person_a = add_active_person(
                admin,
                enterprise_id,
                position_a["id"],
                "员工 A",
                "340123199001019985",
            )
            person_b = add_active_person(
                admin,
                enterprise_id,
                position_b["id"],
                "员工 B",
                "340123199001019993",
            )
            claim_a_status, claim_a = add_claim(
                admin, enterprise_id, person_a["id"], "项目 A"
            )
            claim_b_status, claim_b = add_claim(
                admin, enterprise_id, person_b["id"], "项目 B"
            )
            assert claim_a_status == claim_b_status == 200
            ok(
                "POST",
                f"/api/claims/{claim_b['id']}/documents",
                admin,
                {
                    "name": "项目 B 材料",
                    "url": "https://example.com/b.pdf",
                    "doc_type": "diagnosis",
                },
            )

            assert {
                row["actual_employer_id"]
                for row in ok("GET", "/api/positions", manager_token)
            } == {employer_a["id"]}
            assert {
                row["id"] for row in ok("GET", "/api/insured", manager_token)
            } == {person_a["id"]}
            assert {
                row["id"] for row in ok("GET", "/api/claims", manager_token)
            } == {claim_a["id"]}
            assert call(
                "PATCH",
                f"/api/insured/{person_b['id']}",
                manager_token,
                {"name": "越权修改"},
            )[0] == 403
            assert call(
                "PATCH",
                f"/api/insured/{person_b['id']}/status?status=stopped",
                manager_token,
            )[0] == 403
            assert call(
                "GET",
                f"/api/insured/{person_b['id']}/policy-members",
                manager_token,
            )[0] == 403
            assert call(
                "GET",
                f"/api/positions/{position_b['id']}/videos",
                manager_token,
            )[0] == 403
            assert call(
                "POST",
                f"/api/positions/{position_b['id']}/videos",
                manager_token,
                {"name": "越权视频", "url": "https://example.com/forbidden.mp4"},
            )[0] == 403
            assert call(
                "PATCH",
                f"/api/positions/{position_b['id']}",
                manager_token,
                {
                    "actual_employer_id": employer_b["id"],
                    "actual_employer": employer_b["name"],
                    "name": "越权岗位修改",
                    "occupation_class": "1-3类",
                },
            )[0] == 403
            assert call(
                "DELETE", f"/api/positions/{position_b['id']}", manager_token
            )[0] == 403
            assert call(
                "PATCH",
                f"/api/actual-employers/{employer_b['id']}",
                manager_token,
                {"name": "越权单位修改"},
            )[0] == 403
            assert call(
                "POST",
                "/api/actual-employers",
                manager_token,
                {"name": "越权新建单位"},
            )[0] == 403
            assert add_claim(
                manager_token, enterprise_id, person_b["id"], "越权"
            )[0] == 403
            own_claim_status, own_claim = add_claim(
                manager_token, enterprise_id, person_a["id"], "本人项目"
            )
            assert own_claim_status == 200
            assert call(
                "GET", f"/api/claims/{claim_b['id']}", manager_token
            )[0] == 403
            assert call(
                "PATCH",
                f"/api/claims/{claim_b['id']}",
                manager_token,
                {"description": "越权理赔修改"},
            )[0] == 403
            assert call(
                "GET",
                f"/api/claims/{claim_b['id']}/documents",
                manager_token,
            )[0] == 403
            assert call(
                "POST",
                f"/api/claims/{claim_b['id']}/documents",
                manager_token,
                {
                    "name": "越权材料",
                    "url": "https://example.com/forbidden.pdf",
                    "doc_type": "diagnosis",
                },
            )[0] == 403
            assert call(
                "POST",
                f"/api/claims/{own_claim['id']}/documents",
                manager_token,
                {
                    "name": "本人项目材料",
                    "url": "https://example.com/allowed.pdf",
                    "doc_type": "diagnosis",
                },
            )[0] == 200
            assert call(
                "GET", f"/api/claims/{claim_b['id']}/timeline", manager_token
            )[0] == 403
            assert call(
                "GET", f"/api/claims/{claim_b['id']}/checklist", manager_token
            )[0] == 403

            dashboard = ok("GET", "/api/dashboard", manager_token)
            assert dashboard["people"] == 1
            assert dashboard["active_people"] == 1
            assert dashboard["claims_open"] == 2
            assert dashboard["premium_accounts"] == []
            assert dashboard["usage_balance"] == 0
            assert dashboard["balance_alerts"] == []
            products = ok("GET", "/api/screen/products", manager_token)
            matching_product = next(row for row in products if row["plan_id"] == plan["id"])
            assert matching_product["insured_count"] == 1
            summary = ok("GET", "/api/enrollment/summary", manager_token)
            matching_summary = next(row for row in summary if row["plan_id"] == plan["id"])
            assert matching_summary["insured_count"] == 1
            sent = ok(
                "POST",
                f"/api/enrollment/send?enterprise_id={enterprise_id}&plan_id={plan['id']}&kind=enrollment",
                manager_token,
            )
            assert sent["accepted"] == 1
            emailed = ok(
                "POST",
                f"/api/enrollment/email?enterprise_id={enterprise_id}&plan_id={plan['id']}&kind=enrollment",
                manager_token,
            )
            assert emailed["people_count"] == 1
            assert ok("GET", "/api/enrollment/emails", manager_token) == []
            export_status, export_content = call_bytes(
                "GET", "/api/enrollment/export?kind=enrollment", manager_token
            )
            assert export_status == 200
            export_text = export_content.decode("utf-8-sig")
            assert "员工 A" in export_text
            assert "员工 B" not in export_text

            unauthorized_bulk = call(
                "POST",
                "/api/insured/bulk",
                manager_token,
                {
                    "enterprise_id": enterprise_id,
                    "position_id": position_b["id"],
                    "rows": [
                        {
                            "name": "越权批量员工",
                            "id_number": "110101199003070038",
                            "phone": "",
                        }
                    ],
                },
            )
            assert unauthorized_bulk[0] == 403
            allowed_bulk = ok(
                "POST",
                "/api/insured/bulk",
                manager_token,
                {
                    "enterprise_id": enterprise_id,
                    "position_id": position_a["id"],
                    "rows": [
                        {
                            "name": "授权批量员工",
                            "id_number": "110101199003070038",
                            "phone": "",
                        }
                    ],
                },
            )
            assert allowed_bulk["created"] == 1
            assert upload_import_csv(
                manager_token,
                enterprise_id,
                position_b["id"],
                "越权导入员工",
                "340123199001010091",
            )[0] == 403
            import_status, import_result = upload_import_csv(
                manager_token,
                enterprise_id,
                position_a["id"],
                "授权导入员工",
                "340123199001010091",
            )
            assert import_status == 200
            assert import_result["ok"] is True and import_result["success"] == 1

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
            assert ok("GET", "/api/insured", manager_two_token) == []
            assert ok("GET", "/api/claims", manager_two_token) == []
            revoked_dashboard = ok("GET", "/api/dashboard", manager_two_token)
            assert revoked_dashboard["people"] == 0
            assert revoked_dashboard["active_people"] == 0
            assert revoked_dashboard["claims_open"] == 0

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
