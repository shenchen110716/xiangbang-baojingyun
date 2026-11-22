"""开放平台集中式安全负例 smoke（story-p3-compliance AC8）。

把散落在 foundation/write smoke 里的安全负例收成一份集中清单，作为
合规面的权威回归：任何一条破防都该让这份文件红。

覆盖：
- 签名篡改四口径（method / path / query / body）→ 40100
- 时间戳窗口（±300s 外）与 sign-version 异常 → 40100
- nonce 重放（原样重发同一组头）→ 40100
- 密钥状态：disabled / 已过期 / 轮换超期旧钥 → 40100
- scope 缺失 → 40300；IP 白名单外 → 40300（白名单内放行）
- 跨租户隔离：人员 / 保单 / 申报 / 停保目标 / webhook 端点，一律 404 模糊
  或受理即拒，绝不回显他企业数据
- 限流：窗口内超限 → 42900
- 脱敏出口：无 sensitive scope 的人员读不含明文证件号
- webhook 推送签名正负例：接收端可用 AppSecret 复算，篡改即失配；
  事件只投所属企业端点
"""
import json
import os
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(ROOT / "tests"))

os.environ.setdefault("ID_ENCRYPTION_KEY", "openapi-security-smoke-key")

from openapi_foundation_smoke import (  # noqa: E402
    assert_unified_error, http_call, signed_call,
)
from openapi_write_smoke import make_id  # noqa: E402

ID_A1 = make_id("33010119900101001")   # 企业 A 在保人员
ID_B1 = make_id("44020219910202001")   # 企业 B 在保人员
ID_NEW = make_id("33010119900101002")  # 申报用

RECEIVED: list[dict] = []


class _Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        RECEIVED.append({"path": self.path,
                         "headers": {k: v for k, v in self.headers.items()},
                         "body": self.rfile.read(length)})
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


def _ping(app, key, **kwargs):
    (status, payload, _), headers = signed_call(
        app, key["secret"], key["key_id"], "GET", "/openapi/v1/ping", **kwargs)
    return status, payload, headers


def _issue(session, admin, **kwargs):
    from backend.routers.openapi_admin import (
        OpenApiClientIssueIn, issue_openapi_client,
    )
    return issue_openapi_client(OpenApiClientIssueIn(**kwargs),
                                user=admin, session=session)


# ----------------------------------------------------- 签名 / 时间戳 / nonce

def test_signature_tampering(app, ids):
    """篡改 method / path / query / body 任意一处 → 40100。"""
    key = ids["key_a"]
    for tamper in [("POST", "/openapi/v1/ping", "", b""),
                   ("GET", "/openapi/v1/scope-probe", "", b""),
                   ("GET", "/openapi/v1/ping", "x=1", b""),
                   ("GET", "/openapi/v1/ping", "", b"tampered")]:
        (status, payload, _), _ = signed_call(
            app, key["secret"], key["key_id"], "GET", "/openapi/v1/ping",
            tamper_sig_for=tamper)
        assert_unified_error(status, payload, 401, 40100)
    # 写接口 body 篡改：签 A 体发 B 体
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", "/openapi/v1/enrollments",
        body=b'{"items":[]}',
        tamper_sig_for=("POST", "/openapi/v1/enrollments", "", b'{"items":[{}]}'))
    assert_unified_error(status, payload, 401, 40100)
    # 错 secret 整体失配
    (status, payload, _), _ = signed_call(
        app, "not-the-secret", key["key_id"], "GET", "/openapi/v1/ping")
    assert_unified_error(status, payload, 401, 40100)
    print("  签名篡改（method/path/query/body/secret）全量 40100 ok")


def test_timestamp_window_and_version(app, ids):
    key = ids["key_a"]
    for ts in (int(time.time()) - 400, int(time.time()) + 400):
        status, payload, _ = _ping(app, key, ts=ts)
        assert_unified_error(status, payload, 401, 40100)
    status, payload, _ = _ping(app, key, ts=int(time.time()) - 100)
    assert status == 200, "±300s 窗口内必须放行"
    for version in ("1", None):
        status, payload, _ = _ping(app, key, sign_version=version)
        assert_unified_error(status, payload, 401, 40100)
    print("  时间戳窗口 ±300s + sign-version 强制 v2 ok")


def test_nonce_replay(app, ids):
    key = ids["key_a"]
    status, payload, headers = _ping(app, key)
    assert status == 200, payload
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "GET", "/openapi/v1/ping",
        reuse_headers=headers)
    assert_unified_error(status, payload, 401, 40100)
    print("  nonce 原样重放 40100 ok")


# ------------------------------------------------------------- 密钥生命周期

def test_key_lifecycle_negatives(app, session, admin, ids):
    """disabled / 过期 / 轮换超期旧钥，一律 40100（不泄漏具体状态差异
    之外的信息，响应结构一致）。"""
    from backend.models import OpenApiClient
    from backend.routers.openapi_admin import (
        OpenApiClientUpdateIn, rotate_openapi_client, update_openapi_client,
    )

    # disabled
    issued = _issue(session, admin, enterprise_id=ids["ent_a"],
                    name="停用负例", scopes=["probe:read"], env="prod")
    update_openapi_client(issued["id"], OpenApiClientUpdateIn(status="disabled"),
                          user=admin, session=session)
    status, payload, _ = _ping(app, issued)
    assert_unified_error(status, payload, 401, 40100)

    # 已过期
    expired = _issue(session, admin, enterprise_id=ids["ent_a"],
                     name="过期负例", scopes=["probe:read"], env="prod",
                     expires_at=(datetime.now(timezone.utc)
                                 - timedelta(days=1)).isoformat())
    status, payload, _ = _ping(app, expired)
    assert_unified_error(status, payload, 401, 40100)

    # 轮换：宽限期内旧钥可用，超期 40100；新钥始终可用
    rotating = _issue(session, admin, enterprise_id=ids["ent_a"],
                      name="轮换负例", scopes=["probe:read"], env="prod")
    rotated = rotate_openapi_client(rotating["id"], user=admin, session=session)
    new_key = {"key_id": rotated["key_id"], "secret": rotated["secret"]}
    status, _, _ = _ping(app, rotating)
    assert status == 200, "宽限期内旧 secret 必须可用"
    client = session.get(OpenApiClient, rotating["id"])
    client.rotated_at = datetime.now(timezone.utc) - timedelta(days=7, hours=1)
    session.commit()
    status, payload, _ = _ping(app, rotating)
    assert_unified_error(status, payload, 401, 40100)
    status, _, _ = _ping(app, new_key)
    assert status == 200
    print("  密钥 disabled/过期/轮换超期 全量 40100 ok")


# --------------------------------------------------------- scope / IP 白名单

def test_scope_missing(app, ids):
    key = ids["key_probe"]   # 仅 probe:read
    for method, path in [("GET", "/openapi/v1/insured-persons"),
                         ("GET", "/openapi/v1/policies"),
                         ("GET", "/openapi/v1/accounts"),
                         ("POST", "/openapi/v1/enrollments"),
                         ("GET", "/openapi/v1/webhooks")]:
        (status, payload, _), _ = signed_call(
            app, key["secret"], key["key_id"], method, path,
            body=b"{}" if method == "POST" else b"")
        assert_unified_error(status, payload, 403, 40300)
    status, _, _ = _ping(app, key)
    assert status == 200, "probe:read 自身必须可用"
    print("  scope 缺失 40300 ok")


def test_ip_allowlist(app, ids):
    key = ids["key_ip"]   # 白名单 10.0.0.0/8
    status, payload, _ = _ping(app, key)   # 默认 client 127.0.0.1
    assert_unified_error(status, payload, 403, 40300)
    status, payload, _ = _ping(app, key, client=("10.1.2.3", 51000))
    assert status == 200, payload
    print("  IP 白名单外 40300 / 白名单内放行 ok")


# ------------------------------------------------------------- 跨租户隔离

def _collect_values(obj, key):
    """递归收集 JSON 结构里某个键的所有值（m4 账户响应断言用）。"""
    found = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == key:
                found.append(v)
            found.extend(_collect_values(v, key))
    elif isinstance(obj, list):
        for v in obj:
            found.extend(_collect_values(v, key))
    return found


def test_cross_tenant_isolation(app, session, ids):
    """key_a 探 B 的人员/保单/申报/webhook：404 模糊；停保申报 B 的人：
    受理即拒且不回显他企业任何字段。"""
    key_a, key_b = ids["key_a"], ids["key_b"]

    for path in (f"/openapi/v1/insured-persons/{ids['person_b']}",
                 f"/openapi/v1/insured-persons/{ids['person_b']}/history",
                 f"/openapi/v1/policies/{ids['policy_b']}",
                 f"/openapi/v1/policies/{ids['policy_b']}/members"):
        (status, payload, _), _ = signed_call(
            app, key_a["secret"], key_a["key_id"], "GET", path)
        assert_unified_error(status, payload, 404, 42200)

    # 申报单：A 建的单，B 查 404
    body = json.dumps({"items": [{"name": "安全丙", "id_number": ID_NEW,
                                  "phone": ""}]}, ensure_ascii=False).encode()
    (status, payload, _), _ = signed_call(
        app, key_a["secret"], key_a["key_id"], "POST",
        "/openapi/v1/enrollments", body=body,
        extra_headers={"Idempotency-Key": "sec-dec-1"})
    assert status == 200, payload
    declaration_id = payload["data"]["declaration_id"]
    (status, payload, _), _ = signed_call(
        app, key_b["secret"], key_b["key_id"], "GET",
        f"/openapi/v1/declarations/{declaration_id}")
    assert_unified_error(status, payload, 404, 42200)

    # 停保申报指向 B 的人员：受理即拒「人员不存在」，不回显 B 的姓名
    body = json.dumps({"items": [{"person_id": ids["person_b"]}]}).encode()
    (status, payload, _), _ = signed_call(
        app, key_a["secret"], key_a["key_id"], "POST",
        "/openapi/v1/terminations", body=body,
        extra_headers={"Idempotency-Key": "sec-term-1"})
    assert status == 200, payload
    (status, detail, _), _ = signed_call(
        app, key_a["secret"], key_a["key_id"], "GET",
        f"/openapi/v1/declarations/{detail_id(payload)}")
    assert status == 200, detail
    item = detail["data"]["items"][0]
    assert item["status"] == "rejected", item
    flat = json.dumps(detail, ensure_ascii=False)
    assert "乙方人员" not in flat and ID_B1 not in flat, \
        "跨租户停保不得回显他企业人员信息"

    # 人员列表只含本企业
    (status, payload, _), _ = signed_call(
        app, key_a["secret"], key_a["key_id"], "GET",
        "/openapi/v1/insured-persons")
    assert status == 200
    names = {row["name"] for row in payload["data"]["items"]}
    assert "乙方人员" not in names, names

    # webhook 端点：A 删/测 B 的端点 404
    for method, path in [("DELETE", f"/openapi/v1/webhooks/{ids['ep_b']}"),
                         ("POST", f"/openapi/v1/webhooks/{ids['ep_b']}/test")]:
        (status, payload, _), _ = signed_call(
            app, key_a["secret"], key_a["key_id"], method, path,
            body=b"{}" if method == "POST" else b"")
        assert_unified_error(status, payload, 404, 42200)

    # 账户越权（review-p3 m4）：/openapi/v1/accounts 无资源 id 参数、只回
    # 本企业账户，「按 id 探他企业」在结构上 N/A——所以这里以响应内容断言
    # 补齐 AC8 清单：A 的账户响应不得泄漏 B 企业的任何信息。
    (status, payload, _), _ = signed_call(
        app, key_a["secret"], key_a["key_id"], "GET", "/openapi/v1/accounts")
    assert status == 200, payload
    flat = json.dumps(payload, ensure_ascii=False)
    assert "安全smoke企业B" not in flat and str(ids["ent_b"]) not in \
        {str(v) for v in _collect_values(payload, "enterprise_id")}, flat
    print("  跨租户（人员/保单/申报/停保/webhook/账户）404 模糊·零回显 ok")


def detail_id(payload) -> int:
    return payload["data"]["declaration_id"]


# ------------------------------------------------------------------- 限流

def test_rate_limit(app, session, admin, ids):
    limited = _issue(session, admin, enterprise_id=ids["ent_a"],
                     name="限流负例", scopes=["probe:read"], env="prod")
    old_limit = os.environ.get("OPENAPI_RATE_LIMIT_PER_SEC")
    old_window = os.environ.get("OPENAPI_RATE_LIMIT_WINDOW_SECONDS")
    os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "3"
    os.environ["OPENAPI_RATE_LIMIT_WINDOW_SECONDS"] = "60"
    try:
        for _ in range(3):
            status, payload, _ = _ping(app, limited)
            assert status == 200, payload
        status, payload, _ = _ping(app, limited)
        assert_unified_error(status, payload, 429, 42900)
    finally:
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = old_limit or "10"
        os.environ["OPENAPI_RATE_LIMIT_WINDOW_SECONDS"] = old_window or "1"
    print("  限流窗口内第 4 次 42900 ok")


# ------------------------------------------------------------- 脱敏出口

def test_masked_exits(app, ids):
    """无 insured:read_sensitive 的读出口绝不含明文证件号。"""
    key = ids["key_a"]   # 有 insured:read，无 sensitive
    for path in ("/openapi/v1/insured-persons",
                 f"/openapi/v1/insured-persons/{ids['person_a']}",
                 f"/openapi/v1/policies/{ids['policy_a']}/members"):
        (status, payload, _), _ = signed_call(
            app, key["secret"], key["key_id"], "GET", path)
        assert status == 200, payload
        flat = json.dumps(payload, ensure_ascii=False)
        assert ID_A1 not in flat and ID_B1 not in flat, \
            f"{path} 泄漏明文证件号"
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "GET",
        f"/openapi/v1/insured-persons/{ids['person_a']}")
    masked = payload["data"]["id_number"]
    assert masked == f"{ID_A1[:3]}{'*' * 11}{ID_A1[-4:]}", masked
    print("  无 sensitive scope 出口全脱敏 ok")


# ----------------------------------------------------- webhook 推送签名负例

def test_webhook_push_signature(app, session, ids):
    from backend.core.api_keys import sign_request_v2
    from backend.core.db import SessionLocal
    from backend.services.openapi.background import run_tick_once

    key = ids["key_a"]
    RECEIVED.clear()
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "POST",
        f"/openapi/v1/webhooks/{ids['ep_a']}/test", body=b"{}")
    assert status == 200, payload
    with SessionLocal() as tick_session:
        run_tick_once(tick_session)
    deadline = time.time() + 5
    while not RECEIVED and time.time() < deadline:
        time.sleep(0.05)
    assert RECEIVED, "webhook.test 必须送达本地接收端"

    sample = RECEIVED[0]
    headers = {k.lower(): v for k, v in sample["headers"].items()}
    assert headers.get("x-sign-version") == "2", headers
    assert headers.get("x-api-key-id") == key["key_id"], headers
    expected = sign_request_v2(key["secret"], "POST", "/hook-sec-a", "",
                               headers["x-timestamp"], headers["x-nonce"],
                               sample["body"])
    assert headers["x-signature"] == expected, "推送签名必须可用 AppSecret 复算"
    for bad in (sample["body"] + b"x",):
        assert sign_request_v2(key["secret"], "POST", "/hook-sec-a", "",
                               headers["x-timestamp"], headers["x-nonce"],
                               bad) != headers["x-signature"]
    assert sign_request_v2("wrong-secret", "POST", "/hook-sec-a", "",
                           headers["x-timestamp"], headers["x-nonce"],
                           sample["body"]) != headers["x-signature"]
    # 只投 A 的端点（B 的端点路径不该出现）
    assert all(r["path"] == "/hook-sec-a" for r in RECEIVED), \
        [r["path"] for r in RECEIVED]
    # 事件体无明文证件号
    for r in RECEIVED:
        assert ID_A1 not in r["body"].decode() and ID_B1 not in r["body"].decode()
    print("  webhook 推送签名正负例 + 企业隔离 + 无明文 ok")


# ------------------------------------------------------------------------ run

def run():
    with tempfile.TemporaryDirectory(prefix="xbb-openapi-security-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"
        os.environ["OPENAPI_RATE_LIMIT_WINDOW_SECONDS"] = "1"
        # 本地 HTTP 接收端联调开关（生产默认 https-only，write smoke 同款）
        os.environ["OPENAPI_WEBHOOK_ALLOW_INSECURE"] = "1"

        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core.db import SessionLocal
        from backend.models import (
            Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember,
            User,
        )

        startup()

        server = ThreadingHTTPServer(("127.0.0.1", 0), _Receiver)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_port

        try:
            with SessionLocal() as session:
                admin = session.scalar(select(User).where(User.username == "admin"))
                ent_a = session.scalar(select(Enterprise))
                ent_a.usage_balance = 10000.0
                ent_b = Enterprise(name="安全smoke企业B", usage_balance=10000.0)
                session.add(ent_b)
                session.flush()

                plan = InsurancePlan(name="安全smoke产品", insurer="安全保司",
                                     price=100.0, commission_rate=0.1)
                session.add(plan)
                session.flush()
                person_a = InsuredPerson(enterprise_id=ent_a.id, name="甲方人员",
                                         phone="", id_number=ID_A1,
                                         status="active")
                person_b = InsuredPerson(enterprise_id=ent_b.id, name="乙方人员",
                                         phone="", id_number=ID_B1,
                                         status="active")
                policy_a = Policy(policy_no="SEC-A-1", enterprise_id=ent_a.id,
                                  plan_id=plan.id, status="active")
                policy_b = Policy(policy_no="SEC-B-1", enterprise_id=ent_b.id,
                                  plan_id=plan.id, status="active")
                session.add_all([person_a, person_b, policy_a, policy_b])
                session.flush()
                session.add_all([
                    PolicyMember(policy_id=policy_a.id, person_id=person_a.id,
                                 status="active"),
                    PolicyMember(policy_id=policy_b.id, person_id=person_b.id,
                                 status="active"),
                ])
                session.commit()

                ids = {
                    "ent_a": ent_a.id, "ent_b": ent_b.id,
                    "person_a": person_a.id, "person_b": person_b.id,
                    "policy_a": policy_a.id, "policy_b": policy_b.id,
                }
                broad = ["probe:read", "insured:read", "policy:read",
                         "account:read", "declaration:write", "webhook:manage"]
                ids["key_a"] = _issue(session, admin, enterprise_id=ent_a.id,
                                      name="A 主钥", scopes=broad, env="prod")
                ids["key_b"] = _issue(session, admin, enterprise_id=ent_b.id,
                                      name="B 主钥", scopes=broad, env="prod")
                ids["key_probe"] = _issue(session, admin, enterprise_id=ent_a.id,
                                          name="仅探针", scopes=["probe:read"],
                                          env="prod")
                ids["key_ip"] = _issue(session, admin, enterprise_id=ent_a.id,
                                       name="IP 白名单", scopes=["probe:read"],
                                       env="prod", ip_allowlist=["10.0.0.0/8"])

                # webhook 端点（A/B 各一，推送签名与隔离用）
                for label, key, hook in (("ep_a", ids["key_a"], "/hook-sec-a"),
                                         ("ep_b", ids["key_b"], "/hook-sec-b")):
                    body = json.dumps({
                        "url": f"http://127.0.0.1:{port}{hook}",
                        "events": ["webhook.test", "insured.activated"],
                    }).encode()
                    (status, payload, _), _ = signed_call(
                        app, key["secret"], key["key_id"], "POST",
                        "/openapi/v1/webhooks", body=body)
                    assert status == 200, payload
                    ids[label] = payload["data"]["id"]

                test_signature_tampering(app, ids)
                test_timestamp_window_and_version(app, ids)
                test_nonce_replay(app, ids)
                test_key_lifecycle_negatives(app, session, admin, ids)
                test_scope_missing(app, ids)
                test_ip_allowlist(app, ids)
                test_cross_tenant_isolation(app, session, ids)
                test_rate_limit(app, session, admin, ids)
                test_masked_exits(app, ids)
                test_webhook_push_signature(app, session, ids)
        finally:
            server.shutdown()

    print("openapi security smoke passed")


if __name__ == "__main__":
    run()
