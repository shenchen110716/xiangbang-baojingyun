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

    def upload_video(path, token, content=b"video-test"):
        boundary = "----xbb-video-upload-test"
        data = (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file_ext\"\r\n\r\nmp4\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"wx-temp-file\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n"
        ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(
            f"{base}{path}", data=data, method="POST",
            headers={"Authorization": f"Bearer {token}", "Content-Type": f"multipart/form-data; boundary={boundary}"},
        )
        try:
            with default_opener.open(req, timeout=10) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, (json.loads(raw) if raw else None)

    with tempfile.TemporaryDirectory(prefix="xbb-security-smoke-") as folder:
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        # 捕获子进程输出到文件而不是 DEVNULL：之前静默丢弃，一旦服务器没起来
        # （端口冲突、启动异常等）只会看到"server did not come up in time"，
        # 看不出真正原因；同时轮询时先查进程是否已经退出，提前失败，不用干等满整个超时。
        log_path = Path(folder) / "server.log"
        log_file = open(log_path, "wb")
        proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "backend.app:app", "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT, env=env, stdout=log_file, stderr=subprocess.STDOUT,
        )
        log_file.close()
        try:
            for _ in range(75):
                if proc.poll() is not None:
                    raise RuntimeError(f"server exited early (code {proc.returncode}):\n{log_path.read_text(errors='replace')}")
                try:
                    if call("GET", "/api/health")[0] == 200:
                        break
                except (urllib.error.URLError, ConnectionRefusedError):
                    pass
                time.sleep(0.2)
            else:
                raise RuntimeError(f"server did not come up in time; log:\n{log_path.read_text(errors='replace')}")

            admin = call_json("POST", "/api/auth/login", body={"username": "admin", "password": "admin123", "portal": "admin"})[1]["access_token"]

            # --- source/db/config exposure ---
            # The Vue SPA fallback (serve_frontend in backend/app.py) is an
            # explicit allowlist of known client routes, not a wildcard —
            # unmatched paths (source files, but also just plain typos) must
            # still 404, not silently serve index.html.
            for path in ["/backend/app.py", "/data.db", "/requirements.txt", "/.env.example", "/backend/core/config.py", "/does-not-exist", "/assets/does-not-exist.js"]:
                status, _ = call("GET", path)
                assert status == 404, f"{path} should be blocked, got {status}"
            for path in ["/", "/claims", "/billing", "/login"]:
                status, _ = call("GET", path)
                assert status == 200, f"{path} should serve the SPA shell, got {status}"

            # a real hashed asset referenced by the built index.html must be servable
            index_html = (ROOT / "web" / "dist" / "index.html").read_text()
            asset_match = re.search(r'src="(/assets/[^"]+\.js)"', index_html)
            assert asset_match, "could not find a JS asset reference in web/dist/index.html — did `npm run build` run in web/?"
            status, _ = call("GET", asset_match.group(1))
            assert status == 200, f"{asset_match.group(1)} should serve, got {status}"

            # --- cross-tenant isolation ---
            ent_a = call_json("POST", "/api/enterprises", admin, {"name": "租户A"})[1]
            ent_b = call_json("POST", "/api/enterprises", admin, {"name": "租户B"})[1]
            op_a = call_json("POST", "/api/operators", admin, {"enterprise_id": ent_a["id"], "username": "sec_op_a", "password": "pass1234", "name": "A操作员"})[1]
            # This account drives the legacy enterprise-wide security cases
            # below.  Make that broad access explicit now that newly created
            # enterprise operators default to zero-scope project managers.
            call_json("PATCH", f"/api/operators/{op_a['id']}", admin, {"enterprise_role": "owner"})
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

            # --- WeChat position-video upload + admin deletion ---
            # wx.uploadFile may send a temporary filename with no extension
            # and application/octet-stream. The explicit file_ext form field
            # must keep this valid video upload from being rejected.
            upload_pos = call_json("POST", "/api/positions", token_a, {"actual_employer_id": emp["id"], "actual_employer": emp["name"], "name": "视频上传测试岗位"})[1]
            status, uploaded_video = upload_video(f"/api/positions/{upload_pos['id']}/videos/upload", token_a)
            assert status == 200, f"extensionless WeChat upload should succeed, got {status}: {uploaded_video}"
            assert call("GET", uploaded_video["url"])[0] == 200, "newly uploaded video must be downloadable"
            status, _ = call_json("DELETE", f"/api/position-videos/{uploaded_video['id']}", token_a)
            assert status == 403, "enterprise users must not delete reviewed video records"
            status, deleted = call_json("DELETE", f"/api/position-videos/{uploaded_video['id']}", admin)
            assert status == 200 and deleted["ok"], f"platform video deletion failed: {status}, {deleted}"
            assert call_json("GET", f"/api/positions/{upload_pos['id']}/videos", admin)[1] == []
            deleted_position = next(item for item in call_json("GET", "/api/positions", admin)[1] if item["id"] == upload_pos["id"])
            assert deleted_position["status"] == "pending" and deleted_position["video_count"] == 0

            # --- payment callback idempotency -> exactly one ledger entry ---
            pay = call_json("POST", "/api/payments", admin, {"enterprise_id": ent_a["id"], "account": "usage", "amount": 40})[1]
            # /payments/callback is now admin-only (it credits a balance with no
            # signature verification, so it must not be reachable anonymously).
            status, _ = call_json("POST", "/api/payments/callback", body={"order_no": pay["order_no"], "status": "paid"})
            assert status == 401, f"anonymous /payments/callback must be rejected, got {status}"
            status, _ = call_json("POST", "/api/payments/callback", token_a, {"order_no": pay["order_no"], "status": "paid"})
            assert status == 403, f"non-admin /payments/callback must be rejected, got {status}"
            r1 = call_json("POST", "/api/payments/callback", admin, {"order_no": pay["order_no"], "status": "paid"})[1]
            r2 = call_json("POST", "/api/payments/callback", admin, {"order_no": pay["order_no"], "status": "paid"})[1]
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
            pm_person = call_json("POST", "/api/insured", admin, {"enterprise_id": ent_a["id"], "name": "参保测试员工", "id_number": "340123199001010091", "position_id": pm_pos["id"]})[1]
            status, _ = call_json("PATCH", f"/api/insured/{pm_person['id']}/status?status=active", admin)
            assert status == 200
            status, policies_resp = call_json("GET", "/api/policies", admin)
            assert status == 200 and len(policies_resp) >= 1, f"policies endpoint must return real data now, got {policies_resp}"
            matching = [p for p in policies_resp if p["plan_id"] == pm_plan["id"]]
            assert matching and matching[0]["insured_count"] == 0, f"next-day coverage must not count as active before its effective time, got {matching}"
            status, periods = call_json("GET", f"/api/insured/{pm_person['id']}/policy-members", admin)
            assert status == 200 and periods, "activation must still create a future PolicyMember coverage period"

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

            # --- salesperson accounts must be scoped to their self-service
            # endpoints only, never falling through this codebase's pervasive
            # "if enterprise: restrict, else: full access" negative-scoping
            # pattern into admin-equivalent access. This regressed once
            # already (current_user()'s own role allowlist didn't include
            # "salesperson", so every request 403'd before reaching any
            # endpoint) and the fix for that regressed a second time (widening
            # that allowlist alone let salespeople fall through every
            # negatively-scoped router as if they were admin) — direct
            # function-call tests like tests/salesperson_portal_smoke.py
            # cannot catch either failure mode since they bypass FastAPI's
            # Depends() chain entirely, which is why this lives here instead.
            sp_agent = call_json("POST", "/api/agents", admin, {"username": "sec_sp_a", "password": "pass1234", "name": "安全测试业务员"})[1]
            sp_token = call_json("POST", "/api/auth/login", body={"username": "sec_sp_a", "password": "pass1234", "portal": "salesperson"})[1]["access_token"]
            assert call("GET", "/api/agents/me", sp_token)[0] == 200, "salesperson must be able to read their own commission summary"
            assert call("GET", "/api/auth/me", sp_token)[0] == 200, "salesperson must be able to read their own profile"
            for method, path in [
                ("GET", "/api/enterprises"),
                ("GET", "/api/dashboard"),
                ("GET", "/api/reports"),
                ("GET", "/api/billing"),
                ("GET", "/api/messages"),
                ("GET", "/api/claims"),
                ("GET", "/api/insured"),
                ("GET", "/api/policies"),
                ("GET", f"/api/enterprises/{ent_a['id']}/ledger"),
            ]:
                status, resp = call_json(method, path, sp_token)
                assert status == 403, f"salesperson must not reach {method} {path}, got {status}: {resp}"
            status, insured_attempt = call_json("POST", "/api/insured", sp_token, {"enterprise_id": ent_a["id"], "name": "越权测试", "id_number": "110101199001011234"})
            assert status == 403, f"salesperson must not be able to create insured records at any enterprise, got {status}: {insured_attempt}"
            # non-salesperson accounts must still be rejected by the
            # salesperson portal, and vice versa
            assert call_json("POST", "/api/auth/login", body={"username": "admin", "password": "admin123", "portal": "salesperson"})[0] == 403
            assert call_json("POST", "/api/auth/login", body={"username": "sec_sp_a", "password": "pass1234", "portal": "admin"})[0] == 403

            print("security smoke: ok")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    run()
