"""Insurer-role RBAC: login portal gating, path allowlist, pricing blacklist.

Boots a live server and drives it over real HTTP because the path-scoping
guard in current_user() (backend/core/security.py) is a FastAPI
`Depends()`-chain check that does not run when a router function is called
directly as plain Python — see security_smoke.py / salesperson_portal_access_test.py
for the same pattern and rationale.
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
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _call(base, method, path, token=None, body=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, (json.loads(raw) if raw else None)


def test_insurer_login_requires_insurer_portal(base):
    resp = _call(base, "POST", "/api/auth/login", body={"username": "insurer_pingan", "password": "test1234", "portal": "admin"})
    assert resp[0] == 403, f"expected 403, got {resp}"
    print("test_insurer_login_requires_insurer_portal: OK")


def test_insurer_login_succeeds_on_insurer_portal(base):
    status, body = _call(base, "POST", "/api/auth/login", body={"username": "insurer_login_ok", "password": "test1234", "portal": "insurer"})
    assert status == 200, f"expected 200, got {status}: {body}"
    assert "access_token" in body
    print("test_insurer_login_succeeds_on_insurer_portal: OK")


def test_insurer_blocked_from_out_of_scope_endpoint(base):
    login = _call(base, "POST", "/api/auth/login", body={"username": "insurer_scope_check", "password": "test1234", "portal": "insurer"})
    token = login[1]["access_token"]
    status, body = _call(base, "GET", "/api/enterprises", token=token)
    assert status == 403, f"insurer must not reach /api/enterprises, got {status}: {body}"
    # Sanity-check the allowlist isn't over-broad in the other direction too.
    status, _ = _call(base, "GET", "/api/auth/me", token=token)
    assert status == 200, f"insurer must reach /api/auth/me, got {status}"
    print("test_insurer_blocked_from_out_of_scope_endpoint: OK")


def test_strip_internal_pricing_hides_profit_shows_settlement_price():
    from backend.services.pricing import strip_internal_pricing

    class FakeUser:
        role = "insurer"

    data = {"policy_floor_price": 100.0, "profit_amount": 20.0, "agent_commission_amount": 5.0, "insurance_base_price": 130.0}
    result = strip_internal_pricing(data, FakeUser())
    assert result["policy_floor_price"] == 100.0
    assert result["insurance_base_price"] == 130.0
    assert "profit_amount" not in result
    assert "agent_commission_amount" not in result
    print("test_strip_internal_pricing_hides_profit_shows_settlement_price: OK")


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-insurer-rbac-") as folder:
        db = f"sqlite:///{Path(folder) / 'test.db'}"
        env = dict(os.environ, DATABASE_URL=db, ADMIN_PASSWORD="admin123",
                   ENTERPRISE_PASSWORD="enterprise123", ID_ENCRYPTION_KEY="insurer-rbac-test",
                   INTEGRATION_MODE="mock")

        # Seed insurer users before the server owns the sqlite file.
        os.environ.update(DATABASE_URL=db, ADMIN_PASSWORD="admin123",
                          ENTERPRISE_PASSWORD="enterprise123", ID_ENCRYPTION_KEY="insurer-rbac-test")
        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import Insurer, User
        startup()

        def make_insurer_user(name, username):
            with SessionLocal() as s:
                insurer = Insurer(name=name, contact="张经理", phone="13800000000")
                s.add(insurer)
                s.flush()
                user = User(username=username, password_hash=pwd.hash("test1234"), name="保司账号",
                            role="insurer", insurer_id=insurer.id)
                s.add(user)
                s.commit()

        make_insurer_user("人保", "insurer_pingan")
        make_insurer_user("人保2", "insurer_login_ok")
        make_insurer_user("人保3", "insurer_scope_check")

        port = _free_port()
        base = f"http://127.0.0.1:{port}"
        # 捕获子进程输出到文件而不是 DEVNULL：之前静默丢弃，一旦服务器没起来
        # （端口冲突、启动异常等）只会看到"server did not come up in time"，
        # 看不出真正原因；同时轮询时先查进程是否已经退出，提前失败，不用干等满整个超时。
        log_path = Path(folder) / "server.log"
        log_file = open(log_path, "wb")
        proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "backend.app:app", "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT, env=env, stdout=log_file, stderr=subprocess.STDOUT)
        log_file.close()
        try:
            for _ in range(150):
                if proc.poll() is not None:
                    raise RuntimeError(f"server exited early (code {proc.returncode}):\n{log_path.read_text(errors='replace')}")
                try:
                    urllib.request.urlopen(f"{base}/api/health", timeout=1)
                    break
                except (urllib.error.URLError, ConnectionRefusedError):
                    time.sleep(0.1)
            else:
                raise RuntimeError(f"server did not come up in time; log:\n{log_path.read_text(errors='replace')}")

            test_insurer_login_requires_insurer_portal(base)
            test_insurer_login_succeeds_on_insurer_portal(base)
            test_insurer_blocked_from_out_of_scope_endpoint(base)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()

    test_strip_internal_pricing_hides_profit_shows_settlement_price()

    print("insurer rbac test: PASS")


if __name__ == "__main__":
    run()
