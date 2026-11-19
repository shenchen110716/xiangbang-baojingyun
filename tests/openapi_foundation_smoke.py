"""P0 开放平台地基 smoke（story-p0-foundation AC2–AC10、AC12–AC16）。

既有模式：tempfile 临时 SQLite、import backend 之前设 DATABASE_URL、自带
run()。签名管线（统一响应、请求日志、限流、IP 白名单）必须覆盖真实的
HTTP 请求面——本仓库没有 httpx/TestClient，这里用一个最小的进程内 ASGI
驱动器直接调用 backend.app:app，等价于走真实请求管线但无需起子进程；
管理端点与服务层仍按惯例直接调 router/service 函数。

迁移走通性（AC1 的本地部分）：在隔离 SQLite 上 stamp 到前一 head 后
alembic upgrade head，验证建表语句本身可执行；真实 PostgreSQL 验证由
编排方在生产 VPS 的临时库上做，不在本文件。
"""
import asyncio
import json
import os
import secrets
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

os.environ.setdefault("ID_ENCRYPTION_KEY", "openapi-foundation-smoke-key")

PREV_HEAD = "9c3d1e5f7a2b"


# ---------------------------------------------------------------- ASGI 驱动器

def http_call(app, method, path, *, query="", headers=None, body=b"",
              client=("127.0.0.1", 40000)):
    """进程内直接驱动 ASGI 应用，返回 (status, json_body, headers_dict)。"""
    header_items = [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()]
    if body:
        header_items.append((b"content-type", b"application/json"))
    scope = {
        "type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1", "method": method, "scheme": "http",
        "path": path, "raw_path": path.encode(), "query_string": query.encode(),
        "root_path": "", "headers": header_items,
        "client": client, "server": ("testserver", 80),
    }
    messages = []
    state = {"sent": False}

    async def receive():
        if not state["sent"]:
            state["sent"] = True
            return {"type": "http.request", "body": body, "more_body": False}
        return {"type": "http.disconnect"}

    async def send(message):
        messages.append(message)

    asyncio.run(app(scope, receive, send))
    start = next(m for m in messages if m["type"] == "http.response.start")
    raw = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
    out_headers = {k.decode().lower(): v.decode() for k, v in start.get("headers", [])}
    try:
        parsed = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        parsed = {"_raw": raw.decode(errors="replace")}
    return start["status"], parsed, out_headers


def signed_call(app, secret, key_id, method, path, *, query="", body=b"",
                ts=None, nonce=None, sign_version="2", tamper_sig_for=None,
                client=("127.0.0.1", 40000), extra_headers=None, reuse_headers=None):
    """按 v2 签名规范发请求；tamper_sig_for=(method,path,query,body) 时
    签名按 tamper 的内容算（等价于签名与实际请求不一致）。"""
    from backend.core.api_keys import sign_request_v2

    if reuse_headers is not None:
        headers = dict(reuse_headers)
    else:
        timestamp = str(ts if ts is not None else int(time.time()))
        use_nonce = nonce or secrets.token_hex(8)
        sm, sp, sq, sb = tamper_sig_for or (method, path, query, body)
        signature = sign_request_v2(secret, sm, sp, sq, timestamp, use_nonce, sb)
        headers = {
            "X-Api-Key-Id": key_id, "X-Timestamp": timestamp,
            "X-Nonce": use_nonce, "X-Signature": signature,
        }
        if sign_version is not None:
            headers["X-Sign-Version"] = sign_version
    headers.update(extra_headers or {})
    return http_call(app, method, path, query=query, headers=headers,
                     body=body, client=client), headers


def assert_unified_error(status, payload, want_status, want_code):
    assert status == want_status, f"expected HTTP {want_status}, got {status}: {payload}"
    assert payload["code"] == want_code, f"expected code {want_code}, got {payload}"
    assert "request_id" in payload, payload
    text = json.dumps(payload, ensure_ascii=False)
    assert "Traceback" not in text and "sqlalchemy" not in text.lower(), \
        f"error leaks internals: {text}"


# ---------------------------------------------------------------- 各 AC 场景

def _issue(session, admin, **kwargs):
    from backend.routers.openapi_admin import OpenApiClientIssueIn, issue_openapi_client
    payload = OpenApiClientIssueIn(**kwargs)
    return issue_openapi_client(payload, user=admin, session=session)


def test_admin_issuance_and_sandbox(session, admin, ids):
    """AC10 + AC15（签发侧）：签发/列表/启停/改配，secret 只出现一次；
    沙箱 env 必须绑沙箱企业。"""
    from fastapi import HTTPException

    from backend.models import AuditLog, OpenApiClient, User
    from backend.routers.openapi_admin import (
        OpenApiClientUpdateIn, list_openapi_clients, update_openapi_client,
    )
    from sqlalchemy import func, select

    users_before = session.scalar(select(func.count(User.id)))

    issued = _issue(session, admin, enterprise_id=ids["ent_a"], name="A 主密钥",
                    scopes=["probe:read"], env="prod")
    assert issued["secret"], "签发响应必须一次性返回 secret 明文"
    assert issued["key_id"], issued
    ids["key_a"] = issued["key_id"]
    ids["secret_a"] = issued["secret"]

    issued2 = _issue(session, admin, enterprise_id=ids["ent_a"], name="A 无scope",
                     scopes=[], env="prod")
    ids["key_a_noscope"], ids["secret_a_noscope"] = issued2["key_id"], issued2["secret"]

    issued3 = _issue(session, admin, enterprise_id=ids["ent_b"], name="B 主密钥",
                     scopes=["probe:read"], env="prod")
    ids["key_b"], ids["secret_b"] = issued3["key_id"], issued3["secret"]

    issued4 = _issue(session, admin, enterprise_id=ids["ent_a"], name="限流用",
                     scopes=["probe:read"], env="prod")
    ids["key_rate"], ids["secret_rate"] = issued4["key_id"], issued4["secret"]

    ip_key = _issue(session, admin, enterprise_id=ids["ent_a"], name="IP 白名单",
                    scopes=["probe:read"], env="prod", ip_allowlist=["10.0.0.0/8"])
    ids["key_ip"], ids["secret_ip"] = ip_key["key_id"], ip_key["secret"]

    # 未知 scope 拒绝
    try:
        _issue(session, admin, enterprise_id=ids["ent_a"], scopes=["nope:bad"])
        raise AssertionError("unknown scope must be rejected")
    except HTTPException as exc:
        assert exc.status_code == 422, exc.status_code

    # AC15：sandbox env 绑非沙箱企业 → 422
    try:
        _issue(session, admin, enterprise_id=ids["ent_a"], env="sandbox")
        raise AssertionError("sandbox key bound to non-sandbox enterprise must be 422")
    except HTTPException as exc:
        assert exc.status_code == 422, exc.status_code
    sandbox = _issue(session, admin, enterprise_id=ids["ent_sandbox"], name="沙箱",
                     scopes=["probe:read"], env="sandbox")
    ids["key_sandbox"], ids["secret_sandbox"] = sandbox["key_id"], sandbox["secret"]

    # 列表：永不回 secret
    rows = list_openapi_clients(enterprise_id=ids["ent_a"], user=admin, session=session)
    assert rows and all("secret" not in r and "secret_encrypted" not in r for r in rows), rows
    assert any(r["key_id"] == ids["key_a"] for r in rows)

    # 启停与改配
    disabled = _issue(session, admin, enterprise_id=ids["ent_a"], name="将禁用",
                      scopes=["probe:read"])
    ids["key_disabled"], ids["secret_disabled"] = disabled["key_id"], disabled["secret"]
    client_row = session.scalar(select(OpenApiClient).where(
        OpenApiClient.key_id == ids["key_disabled"]))
    out = update_openapi_client(client_row.id, OpenApiClientUpdateIn(status="disabled"),
                                user=admin, session=session)
    assert out["status"] == "disabled" and "secret" not in out, out

    expired = _issue(session, admin, enterprise_id=ids["ent_a"], name="已过期",
                     scopes=["probe:read"])
    ids["key_expired"], ids["secret_expired"] = expired["key_id"], expired["secret"]
    row = session.scalar(select(OpenApiClient).where(OpenApiClient.key_id == ids["key_expired"]))
    past = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    out = update_openapi_client(row.id, OpenApiClientUpdateIn(expires_at=past),
                                user=admin, session=session)
    assert out["expires_at"], out

    # 改 scopes / ip_allowlist
    row = session.scalar(select(OpenApiClient).where(OpenApiClient.key_id == ids["key_ip"]))
    out = update_openapi_client(
        row.id, OpenApiClientUpdateIn(ip_allowlist=["10.0.0.0/8", "192.168.1.0/24"]),
        user=admin, session=session)
    assert "192.168.1.0/24" in out["ip_allowlist"], out
    out = update_openapi_client(row.id, OpenApiClientUpdateIn(ip_allowlist=["10.0.0.0/8"]),
                                user=admin, session=session)
    assert out["ip_allowlist"] == ["10.0.0.0/8"], out

    # 签发/禁用写入既有 audit_logs
    actions = {a.action for a in session.query(AuditLog).all()}
    assert "openapi_client_issue" in actions and "openapi_client_disable" in actions, actions

    # AC16（主体隔离）：签发密钥不产生 users 行 / JWT 身份
    from sqlalchemy import func as f2
    users_after = session.scalar(select(f2.count(User.id)))
    assert users_after == users_before, "openapi key issuance must not create users rows"
    print("  AC10/AC15 管理端签发/启停/沙箱校验 ok")


def test_signature_v2(app, ids):
    """AC2 + AC3 + AC9（统一响应）。"""
    path = "/openapi/v1/ping"
    # AC2 正例
    (status, payload, _), ok_headers = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path, query="demo=1")
    assert status == 200, (status, payload)
    assert payload["code"] == 0 and payload["message"] == "ok", payload
    assert payload["data"]["enterprise_id"] == ids["ent_a"], payload
    assert payload["data"]["env"] == "prod", payload
    assert payload["request_id"], payload
    ids["ping_request_id"] = payload["request_id"]

    # AC3a 篡改：签名按 demo=1 算、实际请求 demo=2
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path, query="demo=2",
        tamper_sig_for=("GET", path, "demo=1", b""))
    assert_unified_error(status, payload, 401, 40100)
    # 篡改 method
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path,
        tamper_sig_for=("POST", path, "", b""))
    assert_unified_error(status, payload, 401, 40100)
    # 篡改 path
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path,
        tamper_sig_for=("GET", "/openapi/v1/other", "", b""))
    assert_unified_error(status, payload, 401, 40100)
    # 篡改 body
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path,
        tamper_sig_for=("GET", path, "", b"{}"))
    assert_unified_error(status, payload, 401, 40100)

    # AC3b 时钟偏移 >300s
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path, ts=int(time.time()) - 600)
    assert_unified_error(status, payload, 401, 40100)

    # AC3c 重放同一 (key_id, nonce)
    (status, payload, _), first_headers = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path)
    assert status == 200, (status, payload)
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path, reuse_headers=first_headers)
    assert_unified_error(status, payload, 401, 40100)

    # AC3d 错误 AppSecret
    (status, payload, _), _ = signed_call(
        app, "wrong-secret", ids["key_a"], "GET", path)
    assert_unified_error(status, payload, 401, 40100)

    # AC3e 禁用 / 过期 key
    (status, payload, _), _ = signed_call(
        app, ids["secret_disabled"], ids["key_disabled"], "GET", path)
    assert_unified_error(status, payload, 401, 40100)
    (status, payload, _), _ = signed_call(
        app, ids["secret_expired"], ids["key_expired"], "GET", path)
    assert_unified_error(status, payload, 401, 40100)

    # 无 X-Sign-Version（v1 风格打 openapi）同样拒绝
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", path, sign_version=None)
    assert_unified_error(status, payload, 401, 40100)

    # 未知 key_id 与错签名同一回答（不可枚举 key）
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], "KEY-NOT-EXIST", "GET", path)
    assert_unified_error(status, payload, 401, 40100)
    print("  AC2/AC3 签名 v2 正例与负例 ok")


def test_scope_and_ip(app, ids):
    """AC5 + AC6。"""
    probe = "/openapi/v1/scope-probe"
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", probe)
    assert status == 200 and payload["code"] == 0, (status, payload)

    (status, payload, _), _ = signed_call(
        app, ids["secret_a_noscope"], ids["key_a_noscope"], "GET", probe)
    assert_unified_error(status, payload, 403, 40300)

    # AC6：配置了 ip_allowlist 的 key，非白名单来源 403
    (status, payload, _), _ = signed_call(
        app, ids["secret_ip"], ids["key_ip"], "GET", "/openapi/v1/ping",
        client=("127.0.0.1", 40000))
    assert_unified_error(status, payload, 403, 40300)
    (status, payload, _), _ = signed_call(
        app, ids["secret_ip"], ids["key_ip"], "GET", "/openapi/v1/ping",
        client=("10.1.2.3", 40000))
    assert status == 200, (status, payload)
    # 反代场景（review-p0 B1 可信代理模型）：
    # 1) 直连来自可信代理（默认 127.0.0.1）→ 取 XFF 最后一跳（nginx
    #    $proxy_add_x_forwarded_for 追加的真实来源）→ 命中白名单放行
    (status, payload, _), _ = signed_call(
        app, ids["secret_ip"], ids["key_ip"], "GET", "/openapi/v1/ping",
        client=("127.0.0.1", 40000), extra_headers={"X-Forwarded-For": "10.1.2.3"})
    assert status == 200, (status, payload)
    # 2) 负例：攻击者在白名单外，预置伪造链 "<白名单IP>, <真实IP>"——
    #    最后一跳才是 nginx 追加的真实来源，必须 403（取第一跳就被骗了）
    (status, payload, _), _ = signed_call(
        app, ids["secret_ip"], ids["key_ip"], "GET", "/openapi/v1/ping",
        client=("127.0.0.1", 40000),
        extra_headers={"X-Forwarded-For": "10.1.2.3, 203.0.113.9"})
    assert_unified_error(status, payload, 403, 40300)
    # 3) 负例：直连来源不是可信代理 → XFF 整个忽略，用直连 IP 判定
    (status, payload, _), _ = signed_call(
        app, ids["secret_ip"], ids["key_ip"], "GET", "/openapi/v1/ping",
        client=("203.0.113.9", 40000),
        extra_headers={"X-Forwarded-For": "10.1.2.3"})
    assert_unified_error(status, payload, 403, 40300)
    # 未配置 allowlist 的 key 不限来源
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", "/openapi/v1/ping",
        client=("203.0.113.9", 40000))
    assert status == 200, (status, payload)
    print("  AC5/AC6 scope 与 IP 白名单 ok")


def test_rate_limit(app, session, ids):
    """AC7：PG/DB 固定窗口限流（本地 SQLite 同一张表），429 + Retry-After。"""
    from sqlalchemy import select

    from backend.models import OpenApiRateCounter
    from backend.routers.openapi.common import OpenApiError
    from backend.services.openapi.rate_limit import enforce_rate_limit

    # 服务层确定性用例：窗口 60s 限 3 次，第 4 次 429
    for _ in range(3):
        enforce_rate_limit(session, "rl-direct-key", "probe", 3, 60)
    try:
        enforce_rate_limit(session, "rl-direct-key", "probe", 3, 60)
        raise AssertionError("4th call in window must raise 429")
    except OpenApiError as exc:
        assert exc.http_status == 429 and exc.code == 42900, (exc.http_status, exc.code)
        assert exc.headers and exc.headers.get("Retry-After"), exc.headers
    row = session.scalar(select(OpenApiRateCounter).where(
        OpenApiRateCounter.key_id == "rl-direct-key"))
    assert row is not None and row.count == 4, (row and row.count)

    # HTTP 面：限额压到 3、窗口放大到 60s 保证 8 连发同窗（计数在表里，
    # 不在进程内存）
    os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "3"
    os.environ["OPENAPI_RATE_LIMIT_WINDOW_SECONDS"] = "60"
    try:
        statuses, retry_after_seen, code_seen = [], False, False
        for _ in range(8):
            (status, payload, headers), _ = signed_call(
                app, ids["secret_rate"], ids["key_rate"], "GET", "/openapi/v1/ping")
            statuses.append(status)
            if status == 429:
                retry_after_seen = retry_after_seen or bool(headers.get("retry-after"))
                code_seen = code_seen or payload.get("code") == 42900
        assert 429 in statuses, statuses
        assert retry_after_seen, "429 response must carry Retry-After"
        assert code_seen, "429 body must carry code 42900"
    finally:
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"
        os.environ.pop("OPENAPI_RATE_LIMIT_WINDOW_SECONDS", None)
    session.expire_all()
    counter = session.scalar(select(OpenApiRateCounter).where(
        OpenApiRateCounter.key_id == ids["key_rate"]))
    assert counter is not None, "超限后 openapi_rate_counters 必须存在对应窗口行"

    # 默认限额是 10/s（环境变量未设置时）
    saved = os.environ.pop("OPENAPI_RATE_LIMIT_PER_SEC")
    from backend.services.openapi import auth as auth_module
    assert auth_module.current_rate_limit() == 10, auth_module.current_rate_limit()
    os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = saved
    print("  AC7 固定窗口限流 ok")


def test_request_logs_and_isolation(app, session, ids):
    """AC8 + AC16。"""
    from sqlalchemy import select

    from backend.models import OpenApiRequestLog
    from backend.routers.openapi_admin import list_openapi_request_logs

    session.expire_all()
    # AC8：成功调用有日志且 request_id 一致
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", "/openapi/v1/ping", query="demo=7")
    assert status == 200
    session.expire_all()
    row = session.scalar(select(OpenApiRequestLog).where(
        OpenApiRequestLog.request_id == payload["request_id"]))
    assert row is not None, "every /openapi/v1 call must write openapi_request_logs"
    assert row.key_id == ids["key_a"] and row.enterprise_id == ids["ent_a"], \
        (row.key_id, row.enterprise_id)
    assert row.method == "GET" and row.path == "/openapi/v1/ping", (row.method, row.path)
    assert row.status == 200 and row.latency_ms is not None and row.ip, \
        (row.status, row.latency_ms, row.ip)

    # 失败调用（错签名）也要有日志
    (status, payload, _), _ = signed_call(
        app, "bad-secret", ids["key_a"], "GET", "/openapi/v1/ping")
    assert status == 401
    session.expire_all()
    row = session.scalar(select(OpenApiRequestLog).where(
        OpenApiRequestLog.request_id == payload["request_id"]))
    assert row is not None and row.status == 401, "failed calls must be logged too"

    # AC16：企业 B 的 key 看到的是 B，不可能读到 A
    (status, payload, _), _ = signed_call(
        app, ids["secret_b"], ids["key_b"], "GET", "/openapi/v1/ping")
    assert status == 200 and payload["data"]["enterprise_id"] == ids["ent_b"], payload
    assert payload["data"]["enterprise_id"] != ids["ent_a"]
    (status, payload, _), _ = signed_call(
        app, ids["secret_b"], ids["key_b"], "GET", "/openapi/v1/scope-probe")
    assert status == 200 and payload["data"]["enterprise_id"] == ids["ent_b"], payload

    # AC16：openapi 主体不进 current_user/JWT 体系——同一组签名头打 /api 一律 401
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", "/api/enterprises")
    assert status == 401, f"openapi signature must not grant /api access, got {status}"

    # 管理端日志查询按 key/企业过滤（admin 面）
    admin_logs = list_openapi_request_logs(
        key_id=ids["key_b"], enterprise_id=None, limit=100,
        user=ids["admin"], session=session)
    assert admin_logs and all(r["key_id"] == ids["key_b"] for r in admin_logs), admin_logs
    by_ent = list_openapi_request_logs(
        key_id=None, enterprise_id=ids["ent_b"], limit=100,
        user=ids["admin"], session=session)
    assert by_ent and all(r["enterprise_id"] == ids["ent_b"] for r in by_ent), by_ent
    print("  AC8/AC16 请求日志与企业隔离 ok")


def test_sandbox_runtime(app, ids):
    """AC15（运行侧）：沙箱 key 调 ping 返回 env=sandbox 且绑定沙箱企业。"""
    (status, payload, _), _ = signed_call(
        app, ids["secret_sandbox"], ids["key_sandbox"], "GET", "/openapi/v1/ping")
    assert status == 200, (status, payload)
    assert payload["data"]["env"] == "sandbox", payload
    assert payload["data"]["enterprise_id"] == ids["ent_sandbox"], payload
    print("  AC15 沙箱 key 路由 ok")


def test_unified_helpers_and_errors(app, session, ids):
    """AC9：统一响应助手、错误码分段、未处理异常 50000 不带 traceback、
    游标分页助手。"""
    from fastapi import APIRouter, FastAPI
    from sqlalchemy import select

    from backend.models import OpenApiRequestLog
    from backend.routers.openapi.common import (
        E_BIZ, E_PERM, E_RATE, E_SIGN, E_SYS, OpenApiError, OpenApiRoute, err, ok,
        paginate,
    )

    assert (E_SIGN, E_PERM, E_BIZ, E_RATE, E_SYS) == (40100, 40300, 42200, 42900, 50000)
    body = ok({"x": 1}, "rid-1")
    assert body == {"code": 0, "message": "ok", "data": {"x": 1}, "request_id": "rid-1"}
    response = err(E_PERM, "无权", request_id="rid-2")
    assert response.status_code == 403
    parsed = json.loads(response.body)
    assert parsed["code"] == 40300 and parsed["request_id"] == "rid-2", parsed

    # 42200：参数校验失败（签名覆盖 demo=abc，进得了认证、过不了校验）
    (status, payload, _), _ = signed_call(
        app, ids["secret_a"], ids["key_a"], "GET", "/openapi/v1/ping", query="demo=abc")
    assert_unified_error(status, payload, 422, 42200)

    # /openapi/v1 下不存在的路径也要走统一包 + 落请求日志
    # （review-p0 minor-5：不能回 FastAPI 默认的 {"detail": "Not Found"}）
    status, payload, _ = http_call(app, "GET", "/openapi/v1/no-such-endpoint")
    assert_unified_error(status, payload, 404, 42200)
    nf_log = session.scalar(select(OpenApiRequestLog).where(
        OpenApiRequestLog.path == "/openapi/v1/no-such-endpoint"))
    assert nf_log is not None and nf_log.status == 404, \
        "404 兜底也必须落 openapi_request_logs"

    # 50000：未处理异常走统一包、不回显内部细节（独立小 app，复用同一 Route 类）
    crash_app = FastAPI()
    crash_router = APIRouter(prefix="/openapi/v1", route_class=OpenApiRoute)

    @crash_router.get("/boom")
    def boom():
        raise RuntimeError("internal secret detail xyzzy")

    crash_app.include_router(crash_router)
    status, payload, _ = http_call(crash_app, "GET", "/openapi/v1/boom")
    assert_unified_error(status, payload, 500, 50000)
    assert "xyzzy" not in json.dumps(payload), payload

    # 游标分页：默认 50 上限 200，倒序翻页不重叠
    query = session.query(OpenApiRequestLog).order_by(OpenApiRequestLog.id.desc())
    page1, cursor = paginate(query, None, 2)
    assert len(page1) == 2 and cursor, (len(page1), cursor)
    page2, _ = paginate(query, cursor, 2)
    assert page2 and {r.id for r in page1}.isdisjoint({r.id for r in page2})
    try:
        paginate(query, "not-a-cursor", 2)
        raise AssertionError("invalid cursor must raise")
    except OpenApiError as exc:
        assert exc.code == E_BIZ, exc.code
    _, none_cursor = paginate(query, None, 10000)   # limit 被钳到 200
    assert none_cursor is None or isinstance(none_cursor, str)
    print("  AC9 统一响应/错误码/50000/分页 ok")


def test_background_cleanup(session):
    """AC14：后台 tick 清理 7 天前 nonce、180 天前请求日志、1 天前限流
    计数行（review-p0 M2）。"""
    import time as _time

    from sqlalchemy import select

    from backend.models import IntegrationNonce, OpenApiRateCounter, OpenApiRequestLog
    from backend.services.openapi.background import (
        registered_job_names, run_tick_once,
    )

    now = datetime.now(timezone.utc)
    session.add(IntegrationNonce(key_id="OLD", nonce="old-nonce",
                                 seen_at=now - timedelta(days=8)))
    session.add(IntegrationNonce(key_id="NEW", nonce="new-nonce",
                                 seen_at=now - timedelta(hours=1)))
    session.add(OpenApiRequestLog(request_id="old-log", key_id="OLD", enterprise_id=1,
                                  method="GET", path="/openapi/v1/ping", status=200,
                                  latency_ms=1, ip="127.0.0.1",
                                  created_at=now - timedelta(days=181)))
    session.add(OpenApiRequestLog(request_id="new-log", key_id="NEW", enterprise_id=1,
                                  method="GET", path="/openapi/v1/ping", status=200,
                                  latency_ms=1, ip="127.0.0.1",
                                  created_at=now - timedelta(days=1)))
    epoch_now = int(_time.time())
    session.add(OpenApiRateCounter(key_id="OLD-RC", bucket="default",
                                   window_start=epoch_now - 2 * 86400, count=5))
    session.add(OpenApiRateCounter(key_id="NEW-RC", bucket="default",
                                   window_start=epoch_now - 30, count=5))
    session.commit()

    names = registered_job_names()
    assert "cleanup_integration_nonces" in names and "cleanup_openapi_request_logs" in names, names
    assert "cleanup_openapi_rate_counters" in names, names
    run_tick_once(session)
    session.expire_all()

    assert session.scalar(select(IntegrationNonce).where(
        IntegrationNonce.nonce == "old-nonce")) is None, "7 天前的 nonce 必须被清理"
    assert session.scalar(select(IntegrationNonce).where(
        IntegrationNonce.nonce == "new-nonce")) is not None
    assert session.scalar(select(OpenApiRequestLog).where(
        OpenApiRequestLog.request_id == "old-log")) is None, "180 天前的请求日志必须被清理"
    assert session.scalar(select(OpenApiRequestLog).where(
        OpenApiRequestLog.request_id == "new-log")) is not None
    assert session.scalar(select(OpenApiRateCounter).where(
        OpenApiRateCounter.key_id == "OLD-RC")) is None, \
        "1 天前的限流计数行必须被清理（review-p0 M2）"
    assert session.scalar(select(OpenApiRateCounter).where(
        OpenApiRateCounter.key_id == "NEW-RC")) is not None
    print("  AC14 清理任务 ok")


def test_lifespan_and_loop(ids):
    """AC12 + AC13：单一 lifespan 并入原 startup 四步（startup() 已被本文件
    直接调用过，且 system/recharge smoke 另行验证）；lifespan 启动统一后台
    任务循环，关停时退出；SQLite 下不抢 PG advisory lock 直接执行。"""
    from backend.app import app
    from backend.services.openapi import background

    assert callable(background.register_periodic_job)
    # SQLite（非 PG 方言）下锁恒放行、release 无副作用
    sqlite_lock = background.AdvisoryLock()
    assert sqlite_lock.acquire() is True
    sqlite_lock.release()

    # review-p0 M1：advisory lock 是连接级的——lock 与 unlock 必须发生在
    # 同一条裸连接上，tick 结束后锁释放、连接关闭（假 PG 引擎验证语义）。
    class _FakeResult:
        def __init__(self, value):
            self._value = value

        def scalar(self):
            return self._value

    class _FakeConn:
        def __init__(self, log, lock_ok=True):
            self._log, self._lock_ok = log, lock_ok
            self.closed = False
            self.invalidated = False

        def exec_driver_sql(self, sql):
            self._log.append((id(self), sql))
            if "pg_try_advisory_lock" in sql:
                return _FakeResult(self._lock_ok)
            return _FakeResult(True)

        def close(self):
            self.closed = True

        def invalidate(self):
            self.invalidated = True

    class _FakeEngine:
        dialect = type("_D", (), {"name": "postgresql"})()

        def __init__(self, lock_ok=True):
            self.calls, self.conns, self._lock_ok = [], [], lock_ok

        def connect(self):
            conn = _FakeConn(self.calls, self._lock_ok)
            self.conns.append(conn)
            return conn

    fake = _FakeEngine()
    ran = background.run_locked_tick(fake)
    assert ran is not None and "cleanup_openapi_rate_counters" in ran, ran
    assert len(fake.conns) == 1, "lock/unlock 必须复用同一条裸连接"
    pinned = fake.conns[0]
    same_conn_sql = [sql for cid, sql in fake.calls if cid == id(pinned)]
    assert any("pg_try_advisory_lock" in s for s in same_conn_sql), fake.calls
    assert any("pg_advisory_unlock" in s for s in same_conn_sql), \
        "tick 后必须在同一连接上释放 advisory lock"
    assert pinned.closed and not pinned.invalidated, \
        "tick 后钉住的连接必须关闭（锁已释放，无需作废）"

    # 抢锁失败：跳过本轮、连接立即关闭、不执行任何 job
    busy = _FakeEngine(lock_ok=False)
    assert background.run_locked_tick(busy) is None
    assert busy.conns[0].closed and not any(
        "pg_advisory_unlock" in sql for _, sql in busy.calls)

    async def drive():
        to_app = asyncio.Queue()
        seen = []

        async def receive():
            return await to_app.get()

        async def send(message):
            seen.append(message["type"])

        task = asyncio.create_task(app(
            {"type": "lifespan", "asgi": {"version": "3.0"}}, receive, send))
        await to_app.put({"type": "lifespan.startup"})
        for _ in range(200):
            if "lifespan.startup.complete" in seen:
                break
            await asyncio.sleep(0.02)
        assert "lifespan.startup.complete" in seen, seen
        loop_task = app.state.openapi_background_task
        assert loop_task is not None and not loop_task.done(), \
            "后台任务循环必须随 lifespan 启动"
        await to_app.put({"type": "lifespan.shutdown"})
        await asyncio.wait_for(task, timeout=10)
        assert "lifespan.shutdown.complete" in seen, seen
        assert loop_task.done(), "后台任务循环必须随 lifespan 关停"

    asyncio.run(drive())
    print("  AC12/AC13 lifespan 与后台循环 ok")


def test_migration_on_isolated_sqlite(folder):
    """AC1 本地部分：新迁移在隔离 SQLite 上 stamp 前一 head 后 upgrade head
    走通建表；保持单一 head。"""
    import sqlite3

    mig_db = Path(folder) / "mig.db"
    # 迁移前形态：旧库里 integration_nonces 存在但还没有 seen_at 索引
    with sqlite3.connect(mig_db) as conn:
        conn.execute(
            "CREATE TABLE integration_nonces ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            "key_id VARCHAR(32) NOT NULL, nonce VARCHAR(64) NOT NULL,"
            "seen_at DATETIME NOT NULL)")
        conn.execute("CREATE UNIQUE INDEX ux_nonce_per_key "
                     "ON integration_nonces (key_id, nonce)")

    env = {**os.environ, "DATABASE_URL": f"sqlite:///{mig_db}"}
    for args in (["stamp", PREV_HEAD], ["upgrade", "head"]):
        proc = subprocess.run([sys.executable, "-m", "alembic", *args],
                              cwd=ROOT, env=env, capture_output=True, text=True)
        assert proc.returncode == 0, f"alembic {args} failed:\n{proc.stdout}\n{proc.stderr}"

    heads = subprocess.run([sys.executable, "-m", "alembic", "heads"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
    head_lines = [l for l in heads.stdout.splitlines() if l.strip()]
    assert len(head_lines) == 1, f"must keep a single alembic head:\n{heads.stdout}"

    with sqlite3.connect(mig_db) as conn:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        for table in ("openapi_clients", "openapi_request_logs", "openapi_rate_counters"):
            assert table in tables, f"{table} missing after upgrade: {tables}"
        indexes = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        assert "ix_integration_nonces_seen_at" in indexes, indexes
        client_cols = {r[1] for r in conn.execute("PRAGMA table_info(openapi_clients)")}
        for col in ("key_id", "secret_encrypted", "secondary_secret_encrypted",
                    "rotated_at", "enterprise_id", "scopes", "allowed_employer_ids",
                    "ip_allowlist", "status", "expires_at", "env", "created_at"):
            assert col in client_cols, f"openapi_clients.{col} missing: {client_cols}"
    print("  AC1(本地) 迁移在隔离 SQLite 走通 ok")


def test_v1_compat_unchanged():
    """AC4 辅助断言：v1 签名原语保持原样（全量回归由
    python3 tests/employment_integration_test.py 单独跑）。"""
    from backend.core.api_keys import sign_request, signing_payload

    assert signing_payload("1", "n", b"") == \
        "1\nn\n" + __import__("hashlib").sha256(b"").hexdigest()
    assert len(sign_request("s", "1", "n", b"")) == 64
    print("  AC4(原语不变) ok")


# ------------------------------------------------------------------------ run

def run():
    with tempfile.TemporaryDirectory(prefix="xbb-openapi-foundation-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        # 平时放开限流免互相干扰；AC7 用例内再收紧
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"

        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User

        startup()   # AC12：模块级 startup() 保留可独立调用（存量测试契约）

        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.username == "admin"))
            ent_a = session.scalar(select(Enterprise))
            ent_b = Enterprise(name="隔离企业B", kind="企业", contact="乙", phone="",
                               status="active")
            ent_sandbox = Enterprise(name="开放平台沙箱企业", kind="沙箱",
                                     contact="沙箱", phone="", status="active")
            session.add_all([ent_b, ent_sandbox])
            session.commit()
            ids = {"ent_a": ent_a.id, "ent_b": ent_b.id,
                   "ent_sandbox": ent_sandbox.id, "admin": admin}

            test_admin_issuance_and_sandbox(session, admin, ids)
            test_signature_v2(app, ids)
            test_scope_and_ip(app, ids)
            test_rate_limit(app, session, ids)
            test_request_logs_and_isolation(app, session, ids)
            test_sandbox_runtime(app, ids)
            test_unified_helpers_and_errors(app, session, ids)
            test_background_cleanup(session)

        test_lifespan_and_loop(ids)
        test_migration_on_isolated_sqlite(folder)
        test_v1_compat_unchanged()

    print("openapi foundation smoke passed")


if __name__ == "__main__":
    run()
