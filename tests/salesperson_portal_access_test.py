"""Regression: a salesperson must reach their own portal over HTTP.

The bug: the global guard in current_user (backend/core/security.py) restricts
salesperson accounts to an exact-match allow-list of paths. Phase 5 shipped the
whole /api/agent-portal/* surface but never added it to that set, so every
portal request from a salesperson was rejected with 403 "业务员账号仅可访问业务员
工作台相关接口" — before the route's own gate ever ran.

This slipped through because the existing agent-portal tests assert route-level
dependencies and call the service functions directly; none drives the guard in
current_user over real HTTP. This one boots a live server and does.
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


def _get(base, path, token):
    req = urllib.request.Request(f"{base}{path}",
                                 headers={"Authorization": f"Bearer {token}"}, method="GET")
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status
    except urllib.error.HTTPError as error:
        return error.code


def run():
    with tempfile.TemporaryDirectory(prefix="xbb-sp-access-") as folder:
        db = f"sqlite:///{Path(folder) / 'test.db'}"
        env = dict(os.environ, DATABASE_URL=db, ADMIN_PASSWORD="admin123",
                   ENTERPRISE_PASSWORD="enterprise123", ID_ENCRYPTION_KEY="sp-access-test",
                   INTEGRATION_MODE="mock")

        # Seed a salesperson before the server owns the file.
        os.environ.update(DATABASE_URL=db, ADMIN_PASSWORD="admin123",
                          ENTERPRISE_PASSWORD="enterprise123", ID_ENCRYPTION_KEY="sp-access-test")
        from backend.app import startup
        from backend.core.db import SessionLocal
        from backend.core.security import pwd
        from backend.models import User
        startup()
        with SessionLocal() as session:
            session.add(User(username="sales1", password_hash=pwd.hash("pass1234"),
                             name="业务员1", role="salesperson"))
            session.commit()

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

            login = urllib.request.Request(
                f"{base}/api/auth/login",
                data=json.dumps({"username": "sales1", "password": "pass1234", "portal": "salesperson"}).encode(),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(login) as resp:
                token = json.load(resp)["access_token"]

            portal_paths = [
                "/api/agent-portal/products",
                "/api/agent-portal/balances",
                "/api/agent-portal/statements",
                "/api/agent-portal/payments",
                "/api/agent-portal/commissions/summary",
                "/api/agent-portal/commissions/details",
            ]
            for path in portal_paths:
                code = _get(base, path, token)
                assert code == 200, f"salesperson must reach {path}, got {code}"

            # The guard must still restrict: a salesperson may not reach an admin path.
            assert _get(base, "/api/agents", token) == 403, "salesperson must not reach /api/agents"
        finally:
            proc.terminate()
            proc.wait(timeout=10)

    print("salesperson portal access test: PASS")


if __name__ == "__main__":
    run()
