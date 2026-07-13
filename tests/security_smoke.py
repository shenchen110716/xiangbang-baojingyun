"""Isolated end-to-end security regression test, exercised over real HTTP
against a live uvicorn subprocess (not direct function calls, unlike
system_smoke.py) — several of the protections here are FastAPI
`dependencies=[...]` checks that simply do not run when a router function
is called directly as plain Python, so they can only be verified this way.

Covers the SYSTEM-DESIGN-V4.md section 16.2 scenarios that are actually
implemented in this codebase today:
  - cross-tenant data isolation (enterprise A cannot see enterprise B's data)
  - enterprises cannot self-recharge / directly inflate their own balance
  - source/database/config files are not reachable over HTTP
  - signed file-download links reject tampering and work for the owner
  - duplicate payment callbacks post exactly one ledger entry (idempotency)
  - a token becomes invalid immediately after its owner's password changes
    or their account is deactivated
"""
import json
import os
import re
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
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def run():
    port = _free_port()
    base = f"http://127.0.0.1:{port}"
    default_opener = urllib.request.build_opener()
    no_redirect_opener = urllib.request.build_opener(NoRedirect)

    def call(method, path, token=None, body=None, no_redirect=False):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
        opener = no_redirect_opener if no_redirect else default_opener
        try:
            with opener.open(req, timeout=10) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def call_json(method, path, token=None, body=None):
        status, raw = call(method, path, token, body)
        return status, (json.loads(raw) if raw else None)

    with tempfile.TemporaryDirectory(prefix="xbb-security-smoke-") as folder:
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "backend.app:app", "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
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

            admin = call_json("POST", "/api/auth/login", body={"username": "admin", "password": "admin123", "portal": "admin"})[1]["access_token"]

            # --- source/db/config exposure ---
            for path in ["/backend/app.py", "/data.db", "/requirements.txt", "/.env.example", "/backend/core/config.py"]:
                status, _ = call("GET", path)
                assert status == 404, f"{path} should be blocked, got {status}"
            for path in ["/", "/index.html", "/script.js", "/styles.css"]:
                status, _ = call("GET", path)
                assert status == 200, f"{path} should serve, got {status}"

            # --- cross-tenant isolation ---
            ent_a = call_json("POST", "/api/enterprises", admin, {"name": "租户A"})[1]
            ent_b = call_json("POST", "/api/enterprises", admin, {"name": "租户B"})[1]
            op_a = call_json("POST", "/api/operators", admin, {"enterprise_id": ent_a["id"], "username": "sec_op_a", "password": "pass1234", "name": "A操作员"})[1]
            token_a = call_json("POST", "/api/auth/login", body={"username": "sec_op_a", "password": "pass1234", "portal": "enterprise"})[1]["access_token"]

            status, employers = call_json("GET", "/api/actual-employers", token_a)
            assert status == 200 and employers == [], "enterprise A's own list should be empty and reachable"

            status, body = call_json("PATCH", f"/api/enterprises/{ent_b['id']}", token_a, {"contact": "hacked"})
            assert status == 403, f"enterprise A must not touch enterprise B, got {status}: {body}"

            status, ops = call_json("GET", "/api/operators", token_a)
            assert status == 200 and all(o["enterprise_id"] == ent_a["id"] for o in ops), "operator list must be tenant-scoped"

            # --- enterprise self-recharge blocked ---
            status, body = call_json("POST", f"/api/enterprises/{ent_a['id']}/recharge", token_a, {"account": "premium", "amount": 999})
            assert status == 403, f"enterprise self-recharge must be blocked, got {status}: {body}"

            # --- signed download links ---
            emp = call_json("POST", "/api/actual-employers", admin, {"enterprise_id": ent_a["id"], "name": "安全测试用工单位"})[1]
            pos = call_json("POST", "/api/positions", admin, {"enterprise_id": ent_a["id"], "actual_employer_id": emp["id"], "actual_employer": emp["name"], "name": "安全测试岗位", "occupation_class": "1-3类"})[1]
            call_json("POST", f"/api/positions/{pos['id']}/videos", admin, {"name": "v", "url": "http://example.com/x.mp4"})
            video = call_json("GET", f"/api/positions/{pos['id']}/videos", admin)[1][0]
            signed_url = video["url"]

            status, _ = call("GET", "/uploads/positions/1/whatever.mp4")
            assert status == 404, "legacy anonymous /uploads path must be gone"

            status, _ = call("GET", signed_url, no_redirect=True)
            assert status in (302, 307), f"valid signed link should redirect, got {status}"

            tampered = re.sub(r"token=[a-f0-9]+", "token=" + "0" * 64, signed_url)
            status, body = call_json("GET", tampered)
            assert status == 403, f"tampered download token must be rejected, got {status}: {body}"

            # --- payment callback idempotency -> exactly one ledger entry ---
            pay = call_json("POST", "/api/payments", admin, {"enterprise_id": ent_a["id"], "account": "usage", "amount": 40})[1]
            r1 = call_json("POST", "/api/payments/callback", body={"order_no": pay["order_no"], "status": "paid"})[1]
            r2 = call_json("POST", "/api/payments/callback", body={"order_no": pay["order_no"], "status": "paid"})[1]
            assert r1["idempotent"] is False and r2["idempotent"] is True, "second callback must be recognized as a duplicate"
            ledger = call_json("GET", f"/api/enterprises/{ent_a['id']}/ledger", admin)[1]
            payment_entries = [e for e in ledger["entries"] if e["business_type"] == "payment" and e["business_id"] == pay["order_no"]]
            assert len(payment_entries) == 1, f"duplicate callback must not double-post the ledger, found {len(payment_entries)}"
            assert ledger["reconciliation"] == [], f"ledger must reconcile with cached balances, got {ledger['reconciliation']}"

            # --- PolicyMember bridge: activating a person over real HTTP must
            # produce a real, non-empty GET /api/policies response. This is the
            # one thing system_smoke.py's direct function calls cannot prove —
            # they bypass FastAPI's request/response cycle entirely (same reason
            # this whole file exists), so a wiring bug in the route/response
            # model would slip through there but not here.
            pm_plan = call_json("POST", "/api/plans", admin, {"insurer": "安全测试保司", "name": "安全测试方案", "price": 100, "commission_rate": .2, "profit_amount": 10})[1]
            pm_pos = call_json("POST", "/api/positions", admin, {"enterprise_id": ent_a["id"], "actual_employer_id": emp["id"], "actual_employer": emp["name"], "name": "参保测试岗位", "occupation_class": "1-3类"})[1]
            call_json("POST", f"/api/positions/{pm_pos['id']}/videos", admin, {"name": "v", "url": "http://example.com/x.mp4"})
            call_json("PATCH", f"/api/positions/{pm_pos['id']}/review", admin, {"status": "approved", "occupation_class": "1-3类", "plan_id": pm_plan["id"]})
            pm_person = call_json("POST", "/api/insured", admin, {"enterprise_id": ent_a["id"], "name": "参保测试员工", "id_number": "340123199001010099", "position_id": pm_pos["id"]})[1]
            status, _ = call_json("PATCH", f"/api/insured/{pm_person['id']}/status?status=active", admin)
            assert status == 200
            status, policies_resp = call_json("GET", "/api/policies", admin)
            assert status == 200 and len(policies_resp) >= 1, f"policies endpoint must return real data now, got {policies_resp}"
            matching = [p for p in policies_resp if p["plan_id"] == pm_plan["id"]]
            assert matching and matching[0]["insured_count"] >= 1, f"policy must count the newly-activated person, got {matching}"

            # --- session invalidation on password change ---
            status, _ = call_json("GET", "/api/auth/me", token_a)
            assert status == 200
            call_json("PATCH", "/api/auth/password", token_a, {"current_password": "pass1234", "new_password": "newpass456"})
            status, _ = call_json("GET", "/api/auth/me", token_a)
            assert status == 401, "token must be invalidated immediately after its own password change"
            fresh_token = call_json("POST", "/api/auth/login", body={"username": "sec_op_a", "password": "newpass456", "portal": "enterprise"})[1]["access_token"]
            assert call("GET", "/api/auth/me", fresh_token)[0] == 200

            # --- session invalidation on admin-initiated deactivation ---
            op_c = call_json("POST", "/api/operators", admin, {"enterprise_id": ent_a["id"], "username": "sec_op_c", "password": "pass1234", "name": "C操作员"})[1]
            token_c = call_json("POST", "/api/auth/login", body={"username": "sec_op_c", "password": "pass1234", "portal": "enterprise"})[1]["access_token"]
            assert call("GET", "/api/auth/me", token_c)[0] == 200
            call_json("PATCH", f"/api/operators/{op_c['id']}", admin, {"active": False})
            status, _ = call_json("GET", "/api/auth/me", token_c)
            assert status == 401, "token must be invalidated immediately after admin deactivates the account"

            print("security smoke: ok")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    run()
