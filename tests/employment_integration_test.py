"""External employment event API auth and idempotency (v4.2 §7.3).

This endpoint is reachable by third parties, so its refusals are the security
boundary: bad or missing signatures, stale timestamps, replayed nonces, and any
attempt to widen scope through the request body.
"""
import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

ID_KEY = "integration-smoke-key"
os.environ.setdefault("ID_ENCRYPTION_KEY", ID_KEY)

VALID_ID = "340123199001011238"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run() -> None:
    from backend.core.api_keys import sign_request

    port = _free_port()
    base = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener()

    def raw_post(path, body: dict, headers: dict):
        payload = json.dumps(body).encode()
        request = urllib.request.Request(
            f"{base}{path}", data=payload,
            headers={"Content-Type": "application/json", **headers}, method="POST")
        try:
            with opener.open(request, timeout=15) as response:
                data = response.read()
                return response.status, json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            data = error.read()
            try:
                return error.code, json.loads(data) if data else None
            except json.JSONDecodeError:
                return error.code, {"detail": data.decode(errors="replace")}

    def signed(body: dict, *, secret: str, key_id: str, ts=None, nonce=None):
        payload = json.dumps(body).encode()
        timestamp = str(ts if ts is not None else int(datetime.now(timezone.utc).timestamp()))
        nonce = nonce or secrets.token_hex(8)
        return {
            "X-Api-Key-Id": key_id,
            "X-Timestamp": timestamp,
            "X-Nonce": nonce,
            "X-Signature": sign_request(secret, timestamp, nonce, payload),
        }

    with tempfile.TemporaryDirectory(prefix="xbb-integration-") as folder:
        db_path = Path(folder) / "test.db"
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{db_path}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        env["ID_ENCRYPTION_KEY"] = ID_KEY
        os.environ["DATABASE_URL"] = f"sqlite:///{db_path}"

        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "backend.app:app",
             "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            for _ in range(50):
                try:
                    request = urllib.request.Request(f"{base}/api/health")
                    with opener.open(request, timeout=5):
                        break
                except (urllib.error.URLError, ConnectionRefusedError):
                    time.sleep(0.2)
            else:
                raise RuntimeError("server did not come up in time")

            ctx = _seed(db_path)
            _test_missing_or_bad_signature_rejected(raw_post, signed, ctx)
            _test_stale_timestamp_rejected(raw_post, signed, ctx)
            _test_body_cannot_widen_scope(raw_post, signed, ctx)
            _test_event_is_accepted_and_idempotent(raw_post, signed, ctx)
            _test_replayed_nonce_rejected(raw_post, signed, ctx)
            _test_batch_has_no_partial_commit(raw_post, signed, ctx)
        finally:
            process.terminate()
            process.wait(timeout=10)

    print("employment integration tests passed")


def _seed(db_path):
    """Create the enterprise, employers and API key directly; the admin UI for
    issuing keys is not part of this phase."""
    from sqlalchemy import create_engine, select
    from sqlalchemy.orm import Session

    from backend.core.api_keys import issue_key_secret
    from backend.models import ActualEmployer, Enterprise, IntegrationApiKey

    engine = create_engine(f"sqlite:///{db_path}")
    with Session(engine) as session:
        enterprise = session.scalar(select(Enterprise))
        other = Enterprise(name="他企业")
        session.add(other)
        session.flush()

        employer_a = ActualEmployer(enterprise_id=enterprise.id, name="接入项目 A")
        employer_b = ActualEmployer(enterprise_id=enterprise.id, name="接入项目 B")
        session.add_all([employer_a, employer_b])
        session.flush()

        secret, cipher = issue_key_secret()
        key = IntegrationApiKey(
            enterprise_id=enterprise.id, name="测试接入", key_id="KEY-TEST-1",
            secret_cipher=cipher, allowed_employer_ids=str(employer_a.id),
            active=True, created_at=datetime.now(timezone.utc))
        session.add(key)
        session.commit()

        return {
            "secret": secret, "key_id": "KEY-TEST-1",
            "enterprise_id": enterprise.id, "other_enterprise_id": other.id,
            "employer_a": employer_a.id, "employer_b": employer_b.id,
            "db": db_path,
        }


def _event(ctx, *, employer_id=None, source="EVT-1", id_number=VALID_ID):
    return {
        "actual_employer_id": employer_id or ctx["employer_a"],
        "person_name": "张三",
        "id_number": id_number,
        "external_employee_no": "E001",
        "actual_hire_at": "2026-03-01",
        "feedback_reported_at": "2026-03-02",
        "source_event_id": source,
    }


PATH = "/api/integrations/employment-events"


def _test_missing_or_bad_signature_rejected(raw_post, signed, ctx):
    status, _ = raw_post(PATH, _event(ctx), {})
    assert status == 401, f"unsigned must be 401, got {status}"

    headers = signed(_event(ctx), secret="wrong-secret", key_id=ctx["key_id"])
    status, _ = raw_post(PATH, _event(ctx), headers)
    assert status == 401, f"bad signature must be 401, got {status}"

    headers = signed(_event(ctx), secret=ctx["secret"], key_id="KEY-DOES-NOT-EXIST")
    status, _ = raw_post(PATH, _event(ctx), headers)
    assert status == 401, f"unknown key must be 401, got {status}"
    print("  bad signature rejected ok")


def _test_stale_timestamp_rejected(raw_post, signed, ctx):
    stale = int(datetime.now(timezone.utc).timestamp()) - 600
    headers = signed(_event(ctx), secret=ctx["secret"], key_id=ctx["key_id"], ts=stale)
    status, _ = raw_post(PATH, _event(ctx), headers)
    assert status == 401, f"stale timestamp must be 401, got {status}"
    print("  stale timestamp rejected ok")


def _test_body_cannot_widen_scope(raw_post, signed, ctx):
    """认证身份固定绑定企业及允许的实际工作单位，Body 不能扩大范围（§7.3）。"""
    body = _event(ctx, employer_id=ctx["employer_b"], source="EVT-SCOPE")
    status, _ = raw_post(PATH, body, signed(body, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status == 403, f"out-of-scope employer must be 403, got {status}"

    body = _event(ctx, source="EVT-SCOPE-2")
    body["enterprise_id"] = ctx["other_enterprise_id"]
    status, payload = raw_post(PATH, body, signed(body, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status in (200, 403), status
    if status == 200:
        assert payload["enterprise_id"] == ctx["enterprise_id"], \
            "a body enterprise_id must never override the authenticated identity"
    print("  body cannot widen scope ok")


def _test_event_is_accepted_and_idempotent(raw_post, signed, ctx):
    body = _event(ctx, source="EVT-IDEM")
    status, first = raw_post(PATH, body, signed(body, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status == 200, f"valid event must be accepted: {status} {first}"

    # 同一 source_event_id 幂等：返回同一事实，不新建
    status, again = raw_post(PATH, body, signed(body, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status == 200, f"replayed source_event_id must be idempotent: {status} {again}"
    assert again["id"] == first["id"], "idempotent replay must return the same fact"
    print("  idempotent source_event_id ok")


def _test_replayed_nonce_rejected(raw_post, signed, ctx):
    body = _event(ctx, source="EVT-NONCE")
    headers = signed(body, secret=ctx["secret"], key_id=ctx["key_id"])
    status, _ = raw_post(PATH, body, headers)
    assert status == 200, status
    status, _ = raw_post(PATH, body, headers)   # 同一 nonce 重放
    assert status == 409, f"replayed nonce must be 409, got {status}"
    print("  nonce replay rejected ok")


def _test_batch_has_no_partial_commit(raw_post, signed, ctx):
    body = {"events": [
        _event(ctx, source="EVT-B1"),
        _event(ctx, source="EVT-B2", id_number="BAD-ID"),
    ]}
    status, payload = raw_post(f"{PATH}/batch", body,
                               signed(body, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status == 400, f"a bad row must fail the batch, got {status} {payload}"
    rows = payload["detail"]["rows"] if "detail" in payload else payload["rows"]
    assert rows[1]["errors"], payload

    # 确认没有部分提交：好行也不得写入
    probe = {"events": [_event(ctx, source="EVT-B1")]}
    status, payload = raw_post(f"{PATH}/batch", probe,
                               signed(probe, secret=ctx["secret"], key_id=ctx["key_id"]))
    assert status == 200, f"the good row must still be importable: {status} {payload}"
    assert payload["created_facts"] == 1, \
        f"EVT-B1 must not have been committed by the failed batch: {payload}"
    print("  batch has no partial commit ok")


if __name__ == "__main__":
    run()
