"""P2 开放平台写入面 smoke（story-p2-write AC2–AC17，入口 AC19）。

既有模式：tempfile 临时 SQLite、import backend 之前设 DATABASE_URL、自带
run()；HTTP 面复用 openapi_foundation_smoke 的进程内 ASGI 驱动器与 v2
签名助手。webhook 投递用本地 threading HTTPServer 做真实 HTTP 接收端
（AC14 要求在接收端验证投递签名正负例）。

覆盖：迁移隔离走通（AC1 本地）、幂等键全口径（AC2：缺头/回放/异体/处理中/
失败释放/7 天清理）、参保申报受理与受理即拒（AC3/AC4）、draft→pending
落库语义与 external_person_id 回传（AC5）、人工确认驱动的终态翻转与事件
（AC6/AC7）、申报单查询（AC8）、参保状态机（AC9）、充值双通道（AC10/AC11）、
webhook 管理（AC12）、outbox 投递/退避/手动重发（AC13）、投递签名正负例
（AC14）、余额预警（AC15）、申报限流（AC16）、越权专项（AC17）。
"""
import asyncio
import hashlib
import hmac
import json
import os
import subprocess
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

os.environ.setdefault("ID_ENCRYPTION_KEY", "openapi-write-smoke-key")

from openapi_foundation_smoke import (  # noqa: E402  共用 ASGI 驱动与签名助手
    assert_unified_error, http_call, signed_call,
)

P1_HEAD = "c7d2e9f4a1b8"
P2_HEAD = "d8e3f6a2b9c4"

_cleanup_urls: list[str] = []

# ---------------------------------------------------------------- 工具

_ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
_ID_CHECKS = "10X98765432"


def make_id(prefix17: str) -> str:
    """按 GB11643 校验位构造合法身份证号（17 位前缀 → 18 位）。"""
    assert len(prefix17) == 17 and prefix17.isdigit(), prefix17
    total = sum(int(d) * w for d, w in zip(prefix17, _ID_WEIGHTS))
    return prefix17 + _ID_CHECKS[total % 11]


ID_P1 = make_id("11010119900101001")
ID_P2 = make_id("11010119900101002")
ID_P3 = make_id("11010119900101003")
ID_P4 = make_id("11010119900101004")
ID_DUP = make_id("11010119900101005")       # 预置在保人员
ID_POOR = make_id("11010119900101006")      # 余额不足企业的申报对象
ID_B = make_id("22020219910202001")         # 企业 B
ID_UNDERAGE = make_id("11010120150101001")  # 未满 16 周岁


def _get(app, key, path, query=""):
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "GET", path, query=query)
    return status, payload, headers


def _post_json(app, key, path, obj, idem=None, query=""):
    extra = {"Idempotency-Key": idem} if idem else None
    body = json.dumps(obj, ensure_ascii=False).encode()
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", path, query=query,
        body=body, extra_headers=extra)
    return status, payload, headers


def _post_raw(app, key, path, body, query=""):
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", path, query=query, body=body)
    return status, payload, headers


def _delete(app, key, path):
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "DELETE", path)
    return status, payload, headers


def _issue(session, admin, **kwargs):
    from backend.routers.openapi_admin import OpenApiClientIssueIn, issue_openapi_client
    out = issue_openapi_client(OpenApiClientIssueIn(**kwargs), user=admin, session=session)
    return {"key_id": out["key_id"], "secret": out["secret"]}


def _run_tick():
    from backend.core.db import SessionLocal
    from backend.services.openapi.background import run_tick_once
    with SessionLocal() as session:
        return run_tick_once(session)


def _deliveries(session, enterprise_id=None, event_type=None):
    from sqlalchemy import select
    from backend.models import OpenApiWebhookDelivery
    query = select(OpenApiWebhookDelivery).order_by(OpenApiWebhookDelivery.id.asc())
    if enterprise_id is not None:
        query = query.where(OpenApiWebhookDelivery.enterprise_id == enterprise_id)
    if event_type is not None:
        query = query.where(OpenApiWebhookDelivery.event_type == event_type)
    session.expire_all()
    return session.scalars(query).all()


# ---------------------------------------------------------------- 本地接收端

RECEIVED: list[dict] = []
RECEIVER_STATE = {"code": 200}


class _Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        RECEIVED.append({"path": self.path,
                         "headers": {k: v for k, v in self.headers.items()},
                         "body": body})
        self.send_response(RECEIVER_STATE["code"])
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):  # 静音
        pass


# ---------------------------------------------------------------- 各 AC 场景

def test_migration_on_isolated_sqlite(folder):
    """AC1 本地部分：stamp P1 head 后 upgrade 建出 5 张新表 + enterprises
    预警阈值列；单一 head = P2。"""
    import sqlite3

    mig_db = Path(folder) / "mig.db"
    with sqlite3.connect(mig_db) as conn:
        conn.execute("CREATE TABLE enterprises (id INTEGER PRIMARY KEY)")

    env = {**os.environ, "DATABASE_URL": f"sqlite:///{mig_db}"}
    for args in (["stamp", P1_HEAD], ["upgrade", "head"]):
        proc = subprocess.run([sys.executable, "-m", "alembic", *args],
                              cwd=ROOT, env=env, capture_output=True, text=True)
        assert proc.returncode == 0, f"alembic {args} failed:\n{proc.stdout}\n{proc.stderr}"
    heads = subprocess.run([sys.executable, "-m", "alembic", "heads"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
    head_lines = [l for l in heads.stdout.splitlines() if l.strip()]
    assert len(head_lines) == 1 and P2_HEAD in head_lines[0], heads.stdout

    with sqlite3.connect(mig_db) as conn:
        tables = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")}
        for table in ("openapi_declarations", "openapi_declaration_items",
                      "openapi_webhook_endpoints", "openapi_webhook_deliveries",
                      "openapi_idempotency_keys"):
            assert table in tables, f"{table} missing: {tables}"
        cols = {r[1] for r in conn.execute("PRAGMA table_info(enterprises)")}
        assert "usage_alert_threshold" in cols, cols
        indexes = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        for index in ("ix_openapi_declarations_status",
                      "ix_openapi_declaration_items_person_status",
                      "ix_openapi_webhook_deliveries_status",
                      "ux_openapi_idempotency_key"):
            assert index in indexes, f"{index} missing: {indexes}"
    print("  AC1(本地) P2 迁移在隔离 SQLite 走通 ok")


def test_scope_negatives(app, ids):
    """AC17（scope 面）：无写 scope 的 key 打所有写端点全部 403/40300。"""
    noscope = ids["key_noscope"]
    for method, path in [("POST", "/openapi/v1/enrollments"),
                         ("POST", "/openapi/v1/terminations"),
                         ("GET", "/openapi/v1/declarations"),
                         ("POST", "/openapi/v1/recharges"),
                         ("GET", "/openapi/v1/recharges/bank-1"),
                         ("POST", "/openapi/v1/webhooks"),
                         ("GET", "/openapi/v1/webhooks")]:
        (status, payload, _), _ = signed_call(
            app, noscope["secret"], noscope["key_id"], method, path,
            body=b"{}" if method == "POST" else b"",
            extra_headers={"Idempotency-Key": "scope-probe"})
        assert_unified_error(status, payload, 403, 40300)
    print("  缺 scope 负例（写端点全家） ok")


def test_webhook_management(app, session, ids):
    """AC12：注册（https 强制 + 公网限定 + 环境开关）、(企业,URL) 去重幂等、
    列表、删除、测试事件、每企业上限、跨租户 404。"""
    key_a, key_b = ids["key_a"], ids["key_b"]

    # 非 https → 422
    status, payload, _ = _post_json(app, key_a, "/openapi/v1/webhooks",
                                    {"url": "http://example.com/hook"})
    assert_unified_error(status, payload, 422, 42200)
    # review-p2 M6：https 指向私网/内网/loopback → 一律 422（开关不豁免 https）
    for bad in ("https://10.0.0.1/hook", "https://172.16.0.1/hook",
                "https://192.168.1.2/hook", "https://127.0.0.1/hook",
                "https://169.254.1.1/hook", "https://[::1]/hook",
                "https://localhost/hook"):
        status, payload, _ = _post_json(app, key_a, "/openapi/v1/webhooks",
                                        {"url": bad})
        assert_unified_error(status, payload, 422, 42200)
    # review-p2 M6：关掉豁免开关后，http loopback 也 422（生产姿态）
    os.environ["OPENAPI_WEBHOOK_ALLOW_INSECURE"] = "0"
    try:
        status, payload, _ = _post_json(app, key_a, "/openapi/v1/webhooks",
                                        {"url": "http://127.0.0.1:1/prod-probe"})
        assert_unified_error(status, payload, 422, 42200)
    finally:
        os.environ["OPENAPI_WEBHOOK_ALLOW_INSECURE"] = "1"
    # https 可注册（注册在 B 名下并立即删除，避免污染后续投递）
    status, payload, _ = _post_json(app, key_b, "/openapi/v1/webhooks",
                                    {"url": "https://example.com/hook"})
    assert status == 200, payload
    https_ep = payload["data"]["id"]
    # review-p2 M4：同 URL 重复注册 → 返回已有 endpoint（幂等，不新建）
    status, payload, _ = _post_json(app, key_b, "/openapi/v1/webhooks",
                                    {"url": "https://example.com/hook"})
    assert status == 200 and payload["data"]["id"] == https_ep, payload
    status, payload, _ = _post_json(app, key_a, "/openapi/v1/webhooks",
                                    {"url": ids["hook_a_url"]})
    assert status == 200 and payload["data"]["id"] == ids["ep_a"], payload
    # 跨租户：A 的 key 删/测 B 的 endpoint → 404 模糊
    status, payload, _ = _delete(app, key_a, f"/openapi/v1/webhooks/{https_ep}")
    assert_unified_error(status, payload, 404, 42200)
    status, payload, _ = _post_json(app, key_a,
                                    f"/openapi/v1/webhooks/{https_ep}/test", {})
    assert_unified_error(status, payload, 404, 42200)
    status, payload, _ = _delete(app, key_b, f"/openapi/v1/webhooks/{https_ep}")
    assert status == 200, payload

    # 每企业最多 10 个（B 名下注册到顶再清掉）
    ep_ids = []
    for i in range(10):
        status, payload, _ = _post_json(
            app, key_b, "/openapi/v1/webhooks",
            {"url": f"https://example.com/hook-{i}"})
        assert status == 200, payload
        ep_ids.append(payload["data"]["id"])
    status, payload, _ = _post_json(app, key_b, "/openapi/v1/webhooks",
                                    {"url": "https://example.com/hook-11"})
    assert_unified_error(status, payload, 422, 42200)
    for ep in ep_ids:
        _delete(app, key_b, f"/openapi/v1/webhooks/{ep}")

    # 列表只见本企业（A 只有本地接收端那一个）
    status, payload, _ = _get(app, key_a, "/openapi/v1/webhooks")
    urls = [e["url"] for e in payload["data"]["items"]]
    assert urls == [ids["hook_a_url"]], urls
    status, payload, _ = _get(app, key_b, "/openapi/v1/webhooks")
    assert payload["data"]["items"] == [], payload["data"]

    # 测试事件走真实投递链路
    status, payload, _ = _post_json(
        app, key_a, f"/openapi/v1/webhooks/{ids['ep_a']}/test", {})
    assert status == 200 and payload["data"]["status"] == "pending", payload
    ids["test_event_id"] = payload["data"]["event_id"]
    print("  AC12 webhook 注册/上限/隔离/测试事件 ok")


def test_idempotency(app, session, ids):
    """AC2：缺头 422；同 key 同体回放；同 key 异体 409；处理中 409；
    业务失败释放占位。"""
    from sqlalchemy import select
    from backend.core.db import SessionLocal
    from backend.models import OpenApiDeclaration, OpenApiIdempotencyKey

    key = ids["key_a"]
    body_obj = {"items": [{"name": "幂等甲", "id_number": ID_P2,
                           "external_person_id": "EMP-IDEM"}]}

    # 缺 Idempotency-Key → 422
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments", body_obj)
    assert_unified_error(status, payload, 422, 42200)
    assert "Idempotency-Key" in payload["message"], payload
    # 超长 key → 422
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    body_obj, idem="x" * 129)
    assert_unified_error(status, payload, 422, 42200)

    # 首次受理
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    body_obj, idem="idem-1")
    assert status == 200, payload
    first = payload["data"]
    assert first["status"] == "accepted" and first["item_count"] == 1, first
    count_before = len(session.scalars(select(OpenApiDeclaration)).all())

    # 同 key 同体 → 回放首次响应，不新建申报单
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    body_obj, idem="idem-1")
    assert status == 200 and payload["data"] == first, payload
    session.expire_all()
    count_after = len(session.scalars(select(OpenApiDeclaration)).all())
    assert count_after == count_before, "幂等回放不得重复执行副作用"

    # 同 key 异体 → 409
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    {"items": [{"name": "另一个", "id_number": ID_P3}]},
                                    idem="idem-1")
    assert_unified_error(status, payload, 409, 42200)

    # 首次请求仍在处理中（快照未写）→ 409
    inflight_obj = {"items": [{"name": "处理中", "id_number": ID_P4}]}
    inflight_body = json.dumps(inflight_obj, ensure_ascii=False).encode()
    inflight_hash = hashlib.sha256(
        b"\n".join([b"POST", b"/openapi/v1/enrollments", inflight_body])).hexdigest()
    with SessionLocal() as claim:
        claim.add(OpenApiIdempotencyKey(key_id=key["key_id"], idem_key="idem-inflight",
                                        request_hash=inflight_hash))
        claim.commit()
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    inflight_obj, idem="idem-inflight")
    assert_unified_error(status, payload, 409, 42200)
    assert "处理中" in payload["message"], payload

    # 业务失败（空单 422）→ 占位释放，同 key 修正后可重试成功
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    {"items": []}, idem="idem-retry")
    assert_unified_error(status, payload, 422, 42200)
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/enrollments",
        {"items": [{"name": "幂等乙", "id_number": ID_P3,
                    "external_person_id": "EMP-RETRY"}]},
        idem="idem-retry")
    assert status == 200, payload
    print("  AC2 幂等（缺头/回放/异体/处理中/失败释放） ok")


ID_CRASH = make_id("11010119900101009")


def test_idempotency_crash_window(app, session, ids):
    """review-p2 M3：幂等快照与业务副作用单事务提交——在 ctx.save（唯一一次
    commit）前崩溃，申报/人员全部回滚、占位释放，同 key 重试成功且只产生
    一份副作用。"""
    import logging
    from sqlalchemy import select
    from backend.models import InsuredPerson, OpenApiDeclaration
    from backend.services.openapi.idempotency import IdempotencyContext

    key = ids["key_a"]
    body_obj = {"items": [{"name": "崩溃窗口", "id_number": ID_CRASH,
                           "external_person_id": "EMP-CRASH"}]}

    def dec_count():
        session.expire_all()
        return len(session.scalars(select(OpenApiDeclaration.id).where(
            OpenApiDeclaration.enterprise_id == ids["ent_a"])).all())

    count_before = dec_count()
    original_save = IdempotencyContext.save

    def boom(self, s, data):
        raise RuntimeError("simulated crash before single-transaction commit")

    IdempotencyContext.save = boom
    logging.disable(logging.CRITICAL)   # 预期的 50000 堆栈不刷屏
    try:
        status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                        body_obj, idem="idem-crash")
    finally:
        logging.disable(logging.NOTSET)
        IdempotencyContext.save = original_save
    assert_unified_error(status, payload, 500, 50000)

    # 崩溃窗口内：没有任何已提交的副作用（申报单/人员都不存在）
    assert dec_count() == count_before, "save 前崩溃不得留下已提交的申报单"
    assert session.scalar(select(InsuredPerson.id).where(
        InsuredPerson.id_number == ID_CRASH)) is None, \
        "save 前崩溃不得留下已提交的人员"

    # 占位已释放：同 key 原样重试成功，副作用恰好一份
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    body_obj, idem="idem-crash")
    assert status == 200, payload
    assert dec_count() == count_before + 1
    session.expire_all()
    people = session.scalars(select(InsuredPerson).where(
        InsuredPerson.id_number == ID_CRASH)).all()
    assert len(people) == 1 and people[0].status == "pending", people
    print("  review-p2 M3 崩溃窗口（快照+副作用单事务·key 可重试） ok")


def test_usage_alert_threshold_admin_entry(session, ids):
    """review-p2 M5：usage_alert_threshold 可经管理端 PATCH /api/enterprises
    设置（EnterpriseUpdate 字段 + 既有赋值循环），serialize 回显。"""
    from backend.models import Enterprise
    from backend.routers.enterprises import update_enterprise
    from backend.schemas import EnterpriseUpdate

    out = update_enterprise(ids["ent_b"],
                            EnterpriseUpdate(usage_alert_threshold=123.5),
                            user=ids["admin"], session=session)
    assert out["usage_alert_threshold"] == 123.5, out
    session.expire_all()
    assert session.get(Enterprise, ids["ent_b"]).usage_alert_threshold == 123.5
    # 其余字段不受影响（exclude_unset 语义）
    assert session.get(Enterprise, ids["ent_b"]).name == "隔离企业B"
    # 清回未启用，避免影响余额预警扫描的既有断言
    session.get(Enterprise, ids["ent_b"]).usage_alert_threshold = None
    session.commit()
    print("  review-p2 M5 管理端阈值设置入口 ok")


def test_background_loop_nonblocking():
    """review-p2 B1：background_loop 的 tick 跑在 asyncio.to_thread 里，
    慢出网 HTTP（以 1s 慢任务模拟）不得阻塞事件循环的心跳。"""
    import backend.services.openapi.background as bg

    def slow_job(_session):
        time.sleep(1.0)   # 模拟 webhook 出网最长 5s 超时的慢投递

    bg.register_periodic_job("_b1_slow_probe", slow_job)
    beats: list[float] = []

    async def main():
        stop = asyncio.Event()
        task = asyncio.create_task(bg.background_loop(stop))
        for _ in range(10):
            beats.append(time.monotonic())
            await asyncio.sleep(0.05)
        beats.append(time.monotonic())
        stop.set()
        await task   # tick 结束后 stop 置位即退出（不会等满 30s）

    try:
        asyncio.run(main())
    finally:
        bg._JOBS.pop("_b1_slow_probe", None)
    gaps = [b - a for a, b in zip(beats, beats[1:])]
    assert max(gaps) < 0.5, \
        f"事件循环被 tick 阻塞：最大心跳间隔 {max(gaps):.2f}s（慢任务 1.0s）"
    print("  review-p2 B1 后台 tick 不阻塞事件循环 ok")


def test_idempotency_cleanup(session):
    """AC2 尾部：7 天前的幂等键被后台清理，7 天内的保留。"""
    from sqlalchemy import select
    from backend.models import OpenApiIdempotencyKey

    old = OpenApiIdempotencyKey(
        key_id="cleanup-probe", idem_key="old", request_hash="x",
        created_at=datetime.now(timezone.utc) - timedelta(days=8))
    session.add(old)
    session.commit()
    ran = _run_tick()
    assert "openapi_idempotency_cleanup" in ran, ran
    session.expire_all()
    rows = session.scalars(select(OpenApiIdempotencyKey)).all()
    assert not any(r.key_id == "cleanup-probe" for r in rows), "8 天前的键应被清理"
    assert any(r.idem_key == "idem-1" for r in rows), "7 天内的键必须保留"
    print("  AC2 幂等键 7 天清理 ok")


def test_enrollment_declarations(app, session, ids):
    """AC3/AC4/AC5：受理与受理即拒全分支 + draft→pending 落库语义。"""
    from sqlalchemy import select
    from backend.models import (
        Enterprise, InsuredPerson, OpenApiDeclaration, ParticipationOperation,
    )

    key = ids["key_a"]
    items = [
        {"name": "参保壹", "id_number": ID_P1, "phone": "13800001111",
         "position_id": ids["pos_a"], "external_person_id": "EMP-001"},
        {"name": "证件非法", "id_number": "12345"},
        {"name": "未成年", "id_number": ID_UNDERAGE},
        {"name": "护照客", "id_number": ID_P4, "id_type": "passport"},
        {"name": "别人", "id_number": ID_DUP},                 # 姓名-证件号矛盾
        {"name": "甲已在保", "id_number": ID_DUP},             # 重复在保
        {"name": "参保壹", "id_number": ID_P1},                # 批内重复
    ]
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    {"items": items}, idem="enroll-a")
    assert status == 200, payload
    data = payload["data"]
    assert data["status"] == "accepted" and data["item_count"] == 7, data
    dec_a = data["declaration_id"]
    ids["dec_a"] = dec_a

    status, payload, _ = _get(app, key, f"/openapi/v1/declarations/{dec_a}")
    assert status == 200, payload
    detail = payload["data"]
    assert detail["status"] == "processing", detail
    rows = detail["items"]
    assert [r["status"] for r in rows] == \
        ["pending", "rejected", "rejected", "rejected", "rejected", "rejected",
         "rejected"], rows
    reasons = [r["reason"] or "" for r in rows]
    assert "格式不正确" in reasons[1], reasons
    assert "未满 16 周岁" in reasons[2], reasons
    assert "证件类型" in reasons[3], reasons
    assert "不一致" in reasons[4], reasons
    assert "重复申报" in reasons[5] or "已在保" in reasons[5], reasons
    assert "批内重复" in reasons[6], reasons
    assert rows[0]["external_person_id"] == "EMP-001", rows[0]
    assert rows[0]["person_id"], rows[0]
    # 明细证件号脱敏，响应不含明文
    assert ID_P1 not in json.dumps(payload, ensure_ascii=False), "明细必须脱敏"
    ids["p1"] = rows[0]["person_id"]

    # AC5：落库语义 = draft 建档 + 置 pending + record_operation(enrollment)
    person = session.get(InsuredPerson, ids["p1"])
    assert person.status == "pending", person.status
    assert person.position_id == ids["pos_a"], person.position_id
    assert person.occupation == "搬运工", person.occupation
    op = session.scalar(select(ParticipationOperation).where(
        ParticipationOperation.person_id == person.id,
        ParticipationOperation.operation_type == "enrollment"))
    assert op is not None and op.submitted_by is None, "机器操作 submitted_by 应为 NULL"

    # AC3：>500 人 → 422；空单 → 422
    too_many = {"items": [{"name": "x", "id_number": ID_P4}] * 501}
    status, payload, _ = _post_json(app, key, "/openapi/v1/enrollments",
                                    too_many, idem="enroll-too-many")
    assert_unified_error(status, payload, 422, 42200)
    assert "500" in payload["message"], payload

    # AC4(e)：岗位不属本企业 → 整单 422，且不泄漏他企业岗位存在性
    status, payload, _ = _post_json(
        app, ids["key_b"], "/openapi/v1/enrollments",
        {"items": [{"name": "乙企业人", "id_number": ID_B,
                    "position_id": ids["pos_a"]}]},
        idem="enroll-b-pos")
    assert_unified_error(status, payload, 422, 42200)
    assert "岗位" in payload["message"], payload
    # 不存在的岗位同一句话
    status, payload2, _ = _post_json(
        app, ids["key_b"], "/openapi/v1/enrollments",
        {"items": [{"name": "乙企业人", "id_number": ID_B,
                    "position_id": 999999}]},
        idem="enroll-b-pos2")
    assert payload2["message"] == payload["message"], (payload, payload2)

    # AC4(c)：使用费余额不足 → 整单 422（走 require_usage_funded），不建申报单
    poor = ids["key_poor"]
    poor_body = {"items": [{"name": "穷企业人", "id_number": ID_POOR}]}
    status, payload, _ = _post_json(app, poor, "/openapi/v1/enrollments",
                                    poor_body, idem="enroll-poor")
    assert_unified_error(status, payload, 422, 42200)
    assert "余额不足" in payload["message"], payload
    session.expire_all()
    assert session.scalar(select(OpenApiDeclaration.id).where(
        OpenApiDeclaration.enterprise_id == ids["ent_poor"])) is None
    # 充值后同一 Idempotency-Key 重试成功（AC2 失败释放 × AC4c）
    session.get(Enterprise, ids["ent_poor"]).usage_balance = 500.0
    session.commit()
    status, payload, _ = _post_json(app, poor, "/openapi/v1/enrollments",
                                    poor_body, idem="enroll-poor")
    assert status == 200, payload

    # 批次 B（两人，稍后走 partial_succeeded）与批次 C（一人，走 succeeded）
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/enrollments",
        {"items": [{"name": "幂等甲", "id_number": ID_P2,
                    "external_person_id": "EMP-IDEM"},
                   {"name": "幂等乙", "id_number": ID_P3,
                    "external_person_id": "EMP-RETRY"}]},
        idem="enroll-bc")
    # 幂等测试里 ID_P2/ID_P3 已申报为 pending → 本批两项都应「重复在保」拒绝
    assert status == 200, payload
    status, detail_payload, _ = _get(
        app, key, f"/openapi/v1/declarations/{payload['data']['declaration_id']}")
    assert all(r["status"] == "rejected" for r in detail_payload["data"]["items"])
    assert detail_payload["data"]["status"] == "failed", detail_payload["data"]
    # 批次 B/C 实际用幂等测试建出的两张单（p2、p3 各自 pending）
    from backend.models import OpenApiDeclarationItem
    p2 = session.scalar(select(InsuredPerson).where(
        InsuredPerson.id_number == ID_P2,
        InsuredPerson.enterprise_id == ids["ent_a"]))
    p3 = session.scalar(select(InsuredPerson).where(
        InsuredPerson.id_number == ID_P3,
        InsuredPerson.enterprise_id == ids["ent_a"]))
    assert p2 is not None and p2.status == "pending"
    assert p3 is not None and p3.status == "pending"
    ids["p2"], ids["p3"] = p2.id, p3.id
    ids["dec_b"] = session.scalar(select(OpenApiDeclarationItem.declaration_id).where(
        OpenApiDeclarationItem.person_id == p2.id,
        OpenApiDeclarationItem.status == "pending"))
    ids["dec_c"] = session.scalar(select(OpenApiDeclarationItem.declaration_id).where(
        OpenApiDeclarationItem.person_id == p3.id,
        OpenApiDeclarationItem.status == "pending"))
    print("  AC3/AC4/AC5 参保申报受理与受理即拒 ok")


def test_termination_declarations(app, session, ids):
    """AC7：停保申报同模型；person_id/id_number 定位、负例逐人拒绝。"""
    key = ids["key_a"]
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/terminations",
        {"items": [
            {"person_id": ids["p_dup"], "external_person_id": "EMP-DUP"},
            {"person_id": 999999},                      # 不存在
            {"person_id": ids["p_draft"]},              # 不在保（draft）
            {"id_number": ID_DUP},                      # 批内重复（同一人）
        ]},
        idem="term-a")
    assert status == 200, payload
    data = payload["data"]
    assert data["status"] == "accepted" and data["item_count"] == 4, data
    ids["dec_term"] = data["declaration_id"]

    status, payload, _ = _get(app, key,
                              f"/openapi/v1/declarations/{ids['dec_term']}")
    rows = payload["data"]["items"]
    assert [r["status"] for r in rows] == \
        ["pending", "rejected", "rejected", "rejected"], rows
    assert "人员不存在" in rows[1]["reason"], rows[1]
    assert "不在保" in rows[2]["reason"], rows[2]
    assert "批内重复" in rows[3]["reason"], rows[3]
    assert payload["data"]["status"] == "processing", payload["data"]
    print("  AC7 停保申报受理与负例 ok")


def test_status_machine_and_events(app, session, ids):
    """AC6/AC9：人工确认驱动申报项终态与事件；状态机白名单。"""
    from fastapi import HTTPException
    from sqlalchemy import select
    from backend.models import InsuredPerson, OpenApiDeclaration
    from backend.routers.insured import insured_status

    admin = ids["admin"]

    def change(pid, to):
        return insured_status(pid, status_value=to, user=admin, session=session)

    # AC9 非法流转：draft→active 直跳 400，中文文案
    try:
        change(ids["p_draft"], "active")
        raise AssertionError("draft→active 应被状态机拒绝")
    except HTTPException as exc:
        assert exc.status_code == 400 and "不允许" in exc.detail, exc.detail

    # pending→active：申报项翻 succeeded + insured.activated 入 outbox
    change(ids["p1"], "active")
    session.expire_all()
    activated = _deliveries(session, ids["ent_a"], "insured.activated")
    assert len(activated) == 1, [d.event_type for d in _deliveries(session)]
    event = json.loads(activated[0].payload)
    assert event["person_id"] == ids["p1"], event
    assert event["external_person_id"] == "EMP-001", event
    assert ID_P1 not in activated[0].payload, "webhook payload 不得携带明文证件号"
    # 批次 A 全部终态（1 succeeded + 6 rejected）→ partial_succeeded + completed
    dec_a = session.get(OpenApiDeclaration, ids["dec_a"])
    assert dec_a.status == "partial_succeeded", dec_a.status
    completed = _deliveries(session, ids["ent_a"], "declaration.completed")
    assert any(json.loads(d.payload)["declaration_id"] == ids["dec_a"]
               for d in completed), [d.payload for d in completed]

    # AC9 same→same 放行（幂等重放确认不炸）
    change(ids["p1"], "active")
    # AC9 非法：active→pending 400
    try:
        change(ids["p2"], "pending") if False else None
        pass
    except HTTPException:
        pass
    # p2 仍 pending：pending→active 合法
    change(ids["p2"], "active")
    session.expire_all()
    try:
        change(ids["p2"], "pending")
        raise AssertionError("active→pending 应被状态机拒绝")
    except HTTPException as exc:
        assert exc.status_code == 400 and "不允许" in exc.detail, exc.detail
    dec_b = session.get(OpenApiDeclaration, ids["dec_b"])
    assert dec_b.status == "succeeded", dec_b.status

    # pending→stopped（确认前停保）：悬挂参保申报项作废 → failed + insured.stopped
    change(ids["p3"], "stopped")
    session.expire_all()
    dec_c = session.get(OpenApiDeclaration, ids["dec_c"])
    assert dec_c.status == "failed", dec_c.status
    status, payload, _ = _get(app, ids["key_a"],
                              f"/openapi/v1/declarations/{ids['dec_c']}")
    item = payload["data"]["items"][0]
    assert item["status"] == "rejected" and "停保" in item["reason"], item
    stopped = _deliveries(session, ids["ent_a"], "insured.stopped")
    assert len(stopped) == 1, [d.event_type for d in _deliveries(session)]

    # AC9 stopped→active 复保合法
    change(ids["p3"], "active")
    session.expire_all()
    assert session.get(InsuredPerson, ids["p3"]).status == "active"

    # AC7 终态：停保申报项随 active→stopped 翻 succeeded + insured.stopped
    change(ids["p_dup"], "stopped")
    session.expire_all()
    dec_term = session.get(OpenApiDeclaration, ids["dec_term"])
    assert dec_term.status == "partial_succeeded", dec_term.status
    status, payload, _ = _get(app, ids["key_a"],
                              f"/openapi/v1/declarations/{ids['dec_term']}")
    rows = payload["data"]["items"]
    assert rows[0]["status"] == "succeeded", rows
    stopped = _deliveries(session, ids["ent_a"], "insured.stopped")
    assert len(stopped) == 2, [(d.event_type, d.payload) for d in stopped]
    assert any(json.loads(d.payload).get("external_person_id") == "EMP-DUP"
               for d in stopped), [d.payload for d in stopped]
    print("  AC6/AC7/AC9 确认翻转·事件·状态机 ok")


def test_declaration_queries(app, ids):
    """AC8：列表过滤 + 游标分页 + 跨租户 404。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key, "/openapi/v1/declarations")
    all_items = payload["data"]["items"]
    assert len(all_items) >= 4, all_items
    assert all(set(d) >= {"declaration_id", "kind", "status", "item_count",
                          "created_at", "finished_at"} for d in all_items)

    status, payload, _ = _get(app, key, "/openapi/v1/declarations",
                              query="kind=termination")
    assert all(d["kind"] == "termination" for d in payload["data"]["items"])
    assert any(d["declaration_id"] == ids["dec_term"]
               for d in payload["data"]["items"])
    status, payload, _ = _get(app, key, "/openapi/v1/declarations",
                              query="status=partial_succeeded")
    got = {d["declaration_id"] for d in payload["data"]["items"]}
    assert ids["dec_a"] in got and ids["dec_term"] in got, got
    status, payload, _ = _get(app, key, "/openapi/v1/declarations",
                              query="kind=bogus")
    assert_unified_error(status, payload, 422, 42200)

    # 游标分页逐页走完：无重复无遗漏、id 倒序
    seen, cursor, pages = [], None, 0
    while True:
        query = "limit=2" + (f"&cursor={cursor}" if cursor else "")
        status, payload, _ = _get(app, key, "/openapi/v1/declarations", query=query)
        page = payload["data"]["items"]
        assert len(page) <= 2
        seen.extend(d["declaration_id"] for d in page)
        cursor = payload["data"]["next_cursor"]
        pages += 1
        if not cursor:
            break
        assert pages < 20, "分页死循环"
    assert sorted(seen, reverse=True) == seen and len(set(seen)) == len(seen), seen
    assert set(seen) == {d["declaration_id"] for d in all_items}, seen

    # 跨租户：B 的 key 查 A 的申报单 404；B 列表不含 A 的单
    status, payload, _ = _get(app, ids["key_b"],
                              f"/openapi/v1/declarations/{ids['dec_a']}")
    assert_unified_error(status, payload, 404, 42200)
    status, payload, _ = _get(app, ids["key_b"], "/openapi/v1/declarations")
    assert not (set(seen) & {d["declaration_id"]
                             for d in payload["data"]["items"]}), payload["data"]
    print("  AC8 申报单查询·分页·隔离 ok")


def test_recharges(app, session, ids):
    """AC10/AC11：微信 Native 与银行转账全链路 + 事件 + 负例。"""
    from sqlalchemy import select
    from backend.models import Enterprise, PaymentRecord, RechargeRequest
    from backend.providers import WeChatPayProvider
    from backend.routers.payments import wechat_notify
    from backend.routers.recharge_requests import (
        confirm_recharge_request, reject_recharge_request,
    )

    key = ids["key_a"]
    # 负例：金额 ≤0 / premium 缺保司 / premium+wechat
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/recharges",
        {"account": "usage", "amount": 0, "channel": "wechat_native"},
        idem="re-zero")
    assert_unified_error(status, payload, 422, 42200)
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/recharges",
        {"account": "premium", "amount": 10, "channel": "bank_transfer"},
        idem="re-noins")
    assert_unified_error(status, payload, 422, 42200)
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/recharges",
        {"account": "premium", "insurer": "平安", "amount": 10,
         "channel": "wechat_native"}, idem="re-prem-wx")
    assert_unified_error(status, payload, 422, 42200)

    # AC10：wechat_native（usage）→ code_url + pending
    wx_body = {"account": "usage", "amount": 88.0, "channel": "wechat_native"}
    status, payload, _ = _post_json(app, key, "/openapi/v1/recharges",
                                    wx_body, idem="re-wx")
    assert status == 200, payload
    wx = payload["data"]
    assert wx["status"] == "pending" and wx["code_url"], wx
    assert wx["recharge_id"].startswith("wechat-OPAY-"), wx
    order_no = wx["recharge_id"][len("wechat-"):]
    # 幂等回放：不新建订单
    n_orders = len(session.scalars(select(PaymentRecord)).all())
    status, payload, _ = _post_json(app, key, "/openapi/v1/recharges",
                                    wx_body, idem="re-wx")
    assert payload["data"] == wx, payload
    session.expire_all()
    assert len(session.scalars(select(PaymentRecord)).all()) == n_orders

    # 回调沿用 /api/payments/wechat-notify（mock 验签）→ 入账 + 事件
    class _FakeRequest:
        def __init__(self, headers, body):
            self.headers = headers
            self._body = body

        async def body(self):
            return self._body

    ent = session.get(Enterprise, ids["ent_a"])
    before = ent.usage_balance
    notify_body = json.dumps({"out_trade_no": order_no, "status": "paid",
                              "transaction_id": "wx-txn-openapi"}).encode()
    signature = hmac.new(WeChatPayProvider.MOCK_NOTIFY_SECRET.encode(),
                         notify_body, hashlib.sha256).hexdigest()
    result = asyncio.run(wechat_notify(
        _FakeRequest({"X-Mock-Signature": signature}, notify_body), session))
    assert result["status"] == "paid", result
    session.expire_all()
    assert session.get(Enterprise, ids["ent_a"]).usage_balance == before + 88.0
    status, payload, _ = _get(app, key, f"/openapi/v1/recharges/wechat-{order_no}")
    assert payload["data"]["status"] == "succeeded", payload["data"]
    assert payload["data"]["paid_at"], payload["data"]
    wx_events = [d for d in _deliveries(session, ids["ent_a"], "recharge.succeeded")
                 if json.loads(d.payload)["recharge_id"] == f"wechat-{order_no}"]
    assert len(wx_events) == 1, wx_events

    # AC11：bank_transfer → 收款账户 + 回单 + confirm/reject 事件
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/recharges",
        {"account": "premium", "insurer": "平安", "amount": 500.0,
         "channel": "bank_transfer"}, idem="re-bank")
    assert status == 200, payload
    bank = payload["data"]
    assert bank["recharge_id"].startswith("bank-"), bank
    assert bank["payment_account"], bank   # 已配置收款账户
    bank_id = int(bank["recharge_id"][len("bank-"):])

    # 回单负例：错误扩展名 / 空 body；正例：pdf 原始字节
    status, payload, _ = _post_raw(
        app, key, f"/openapi/v1/recharges/bank-{bank_id}/receipt",
        b"x", query="filename=evil.exe")
    assert_unified_error(status, payload, 422, 42200)
    status, payload, _ = _post_raw(
        app, key, f"/openapi/v1/recharges/bank-{bank_id}/receipt",
        b"%PDF-1.4 receipt", query="filename=receipt.pdf")
    assert status == 200 and payload["data"]["receipt_uploaded"], payload
    item = session.get(RechargeRequest, bank_id)
    session.refresh(item)
    assert item.receipt_file_url, item.receipt_file_url
    _cleanup_urls.append(item.receipt_file_url)

    status, payload, _ = _get(app, key, f"/openapi/v1/recharges/bank-{bank_id}")
    assert payload["data"]["status"] == "pending"
    assert payload["data"]["receipt_uploaded"] is True, payload["data"]

    # admin 确认 → succeeded + recharge.succeeded 事件
    confirm_recharge_request(bank_id, user=ids["admin"], session=session)
    status, payload, _ = _get(app, key, f"/openapi/v1/recharges/bank-{bank_id}")
    assert payload["data"]["status"] == "succeeded", payload["data"]
    bank_events = [d for d in _deliveries(session, ids["ent_a"], "recharge.succeeded")
                   if json.loads(d.payload)["recharge_id"] == f"bank-{bank_id}"]
    assert len(bank_events) == 1, bank_events
    # 已确认后再传回单 → 422
    status, payload, _ = _post_raw(
        app, key, f"/openapi/v1/recharges/bank-{bank_id}/receipt",
        b"%PDF-1.4", query="filename=late.pdf")
    assert_unified_error(status, payload, 422, 42200)

    # 驳回分支 → rejected + reject_reason + recharge.rejected 事件
    status, payload, _ = _post_json(
        app, key, "/openapi/v1/recharges",
        {"account": "usage", "amount": 300.0, "channel": "bank_transfer"},
        idem="re-bank2")
    bank2 = int(payload["data"]["recharge_id"][len("bank-"):])
    reject_recharge_request(bank2, reason="回单不清晰", user=ids["admin"],
                            session=session)
    status, payload, _ = _get(app, key, f"/openapi/v1/recharges/bank-{bank2}")
    assert payload["data"]["status"] == "rejected", payload["data"]
    assert payload["data"]["reject_reason"] == "回单不清晰", payload["data"]
    assert _deliveries(session, ids["ent_a"], "recharge.rejected"), "缺 rejected 事件"

    # 跨租户与不存在：B 查 A 的充值单 404；非法前缀 404
    for path in [f"/openapi/v1/recharges/bank-{bank_id}",
                 f"/openapi/v1/recharges/wechat-{order_no}"]:
        status, payload, _ = _get(app, ids["key_b"], path)
        assert_unified_error(status, payload, 404, 42200)
    status, payload, _ = _get(app, key, "/openapi/v1/recharges/alipay-1")
    assert_unified_error(status, payload, 404, 42200)
    print("  AC10/AC11 充值双通道·回单·事件·隔离 ok")


def test_webhook_delivery_and_signature(app, session, ids):
    """AC13/AC14：outbox 投递到本地 HTTP 接收端；v2 签名验签正负例；
    事件只投给所属企业的 endpoint。"""
    from backend.core.api_keys import sign_request_v2

    RECEIVER_STATE["code"] = 200
    RECEIVED.clear()
    ran = _run_tick()
    assert "openapi_webhook_deliver" in ran, ran
    _run_tick()   # 第二跳：清掉同 tick 顺序差异

    pending = [d for d in _deliveries(session, ids["ent_a"]) if d.status == "pending"]
    assert not pending, [(d.event_type, d.last_error) for d in pending]
    delivered = [d for d in _deliveries(session, ids["ent_a"])
                 if d.status == "delivered"]
    assert delivered and all(d.delivered_at for d in delivered)
    # 事件只投所属企业的 endpoint（AC17）
    assert all(d.endpoint_id == ids["ep_a"] for d in delivered), \
        [(d.endpoint_id, d.event_type) for d in delivered]
    got_types = {json.loads(r["body"].decode())["event_type"] for r in RECEIVED}
    assert {"webhook.test", "insured.activated", "insured.stopped",
            "declaration.completed", "recharge.succeeded",
            "recharge.rejected"} <= got_types, got_types

    # AC14 投递签名正负例：接收端用自己手里的 AppSecret 验签
    sample = RECEIVED[0]
    headers = {k.lower(): v for k, v in sample["headers"].items()}
    assert headers.get("x-sign-version") == "2", headers
    assert headers.get("x-api-key-id") == ids["key_a"]["key_id"], headers
    assert headers.get("x-event-id"), headers
    expected = sign_request_v2(
        ids["key_a"]["secret"], "POST", "/hook-a", "",
        headers["x-timestamp"], headers["x-nonce"], sample["body"])
    assert headers["x-signature"] == expected, "投递签名必须可用 AppSecret 复算"
    tampered = sign_request_v2(
        ids["key_a"]["secret"], "POST", "/hook-a", "",
        headers["x-timestamp"], headers["x-nonce"], sample["body"] + b"x")
    assert tampered != headers["x-signature"], "篡改 body 后签名必须失配"
    wrong_secret = sign_request_v2(
        "not-the-secret", "POST", "/hook-a", "",
        headers["x-timestamp"], headers["x-nonce"], sample["body"])
    assert wrong_secret != headers["x-signature"]

    # 事件体结构 + 无明文证件号
    for r in RECEIVED:
        body = json.loads(r["body"].decode())
        assert set(body) == {"event_id", "event_type", "occurred_at", "data"}, body
        assert body["event_id"] == {k.lower(): v for k, v in
                                    r["headers"].items()}["x-event-id"]
        for raw_id in (ID_P1, ID_P2, ID_P3, ID_DUP):
            assert raw_id not in r["body"].decode(), body
    assert any(json.loads(r["body"].decode())["event_id"] == ids["test_event_id"]
               for r in RECEIVED), "webhook.test 必须真实送达"
    print("  AC13/AC14 投递·签名正负例·企业隔离 ok")


def test_backoff_and_resend(app, session, ids):
    """AC13：失败退避 1m/5m/30m/2h/12h → failed；admin 手动重发后成功。"""
    from backend.models import OpenApiWebhookDelivery
    from backend.routers.openapi_admin import resend_openapi_webhook_delivery
    from backend.services.openapi.webhook_outbox import BACKOFF_SECONDS

    assert BACKOFF_SECONDS == [60, 300, 1800, 7200, 43200], BACKOFF_SECONDS
    RECEIVER_STATE["code"] = 500
    status, payload, _ = _post_json(
        app, ids["key_a"], f"/openapi/v1/webhooks/{ids['ep_a']}/test", {})
    delivery_id = None
    event_id = payload["data"]["event_id"]

    def row():
        session.expire_all()
        from sqlalchemy import select
        return session.scalar(select(OpenApiWebhookDelivery).where(
            OpenApiWebhookDelivery.event_id == event_id))

    _run_tick()
    d = row()
    delivery_id = d.id
    assert d.status == "pending" and d.attempts == 1, (d.status, d.attempts)
    assert d.last_error.startswith("HTTP 500"), d.last_error
    retry_at = d.next_retry_at if d.next_retry_at.tzinfo \
        else d.next_retry_at.replace(tzinfo=timezone.utc)
    gap = (retry_at - datetime.now(timezone.utc)).total_seconds()
    assert 40 <= gap <= 70, f"首次退避应约 60s，实际 {gap}"
    # 未到 next_retry_at 不重试
    _run_tick()
    assert row().attempts == 1

    # 推进 4 次到第 5 次失败，再一次 → failed
    for expect_attempts in (2, 3, 4, 5):
        d = row()
        d.next_retry_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        session.commit()
        _run_tick()
        d = row()
        assert d.attempts == expect_attempts and d.status == "pending", \
            (d.attempts, d.status)
    d = row()
    d.next_retry_at = datetime.now(timezone.utc) - timedelta(seconds=1)
    session.commit()
    _run_tick()
    d = row()
    assert d.status == "failed" and d.attempts == 6, (d.status, d.attempts)
    assert d.next_retry_at is None, d.next_retry_at

    # admin 手动重发 → 修好接收端 → delivered
    RECEIVER_STATE["code"] = 200
    out = resend_openapi_webhook_delivery(delivery_id, user=ids["admin"],
                                          session=session)
    assert out["status"] == "pending", out
    _run_tick()
    d = row()
    assert d.status == "delivered" and d.delivered_at, (d.status, d.last_error)
    # 已投成功的不可再重发
    from fastapi import HTTPException
    try:
        resend_openapi_webhook_delivery(delivery_id, user=ids["admin"],
                                        session=session)
        raise AssertionError("delivered 投递不应允许重发")
    except HTTPException as exc:
        assert exc.status_code == 400, exc

    # endpoint 删除后遗留投递 → failed（不无限重试）
    status, payload, _ = _post_json(app, ids["key_a"], "/openapi/v1/webhooks",
                                    {"url": ids["hook_a_url"] + "-tmp"})
    tmp_ep = payload["data"]["id"]
    status, payload, _ = _post_json(
        app, ids["key_a"], f"/openapi/v1/webhooks/{tmp_ep}/test", {})
    tmp_event = payload["data"]["event_id"]
    _delete(app, ids["key_a"], f"/openapi/v1/webhooks/{tmp_ep}")
    _run_tick()
    from sqlalchemy import select
    session.expire_all()
    tmp_row = session.scalar(select(OpenApiWebhookDelivery).where(
        OpenApiWebhookDelivery.event_id == tmp_event))
    assert tmp_row.status == "failed" and "endpoint" in tmp_row.last_error, \
        (tmp_row.status, tmp_row.last_error)
    print("  AC13 退避·failed·手动重发·endpoint 删除 ok")


def test_balance_alerts(app, session, ids):
    """AC15：跌破阈值投递一次 balance.low；低位不重发；回升再跌再发。"""
    from backend.models import Enterprise

    ent_c = session.get(Enterprise, ids["ent_c"])
    ent_c.usage_alert_threshold = 100.0
    ent_c.usage_balance = 50.0          # available=50 < 100
    session.commit()
    ran = _run_tick()
    assert "openapi_balance_alerts" in ran, ran
    lows = _deliveries(session, ids["ent_c"], "balance.low")
    assert len(lows) == 1, [(d.event_type, d.payload) for d in lows]
    event = json.loads(lows[0].payload)
    assert event["available"] == 50.0 and event["threshold"] == 100.0, event

    # 低位周期内不重发
    _run_tick()
    assert len(_deliveries(session, ids["ent_c"], "balance.low")) == 1

    # 回升 → 重新武装；再跌 → 再发一次
    ent_c = session.get(Enterprise, ids["ent_c"])
    ent_c.usage_balance = 500.0
    session.commit()
    _run_tick()
    assert len(_deliveries(session, ids["ent_c"], "balance.low")) == 1
    ent_c = session.get(Enterprise, ids["ent_c"])
    ent_c.usage_balance = 10.0
    session.commit()
    _run_tick()
    lows = _deliveries(session, ids["ent_c"], "balance.low")
    assert len(lows) == 2, [(d.payload,) for d in lows]
    # 未设阈值的企业绝不触发（A 没配阈值）
    assert not _deliveries(session, ids["ent_a"], "balance.low")

    # review-p2 minor：没注册 endpoint 的企业跌破阈值不落 low 状态——
    # 否则「先跌破、后注册」会错过本轮低位周期的唯一一次预警。
    from sqlalchemy import select
    from backend.models import AuditLog
    ent_b = session.get(Enterprise, ids["ent_b"])
    ent_b.usage_alert_threshold = 999999.0   # B 此刻没有任何 endpoint
    session.commit()
    _run_tick()
    assert not _deliveries(session, ids["ent_b"], "balance.low")
    state = session.scalar(select(AuditLog.detail).where(
        AuditLog.action == "openapi_balance_low",
        AuditLog.object_id == str(ids["ent_b"]))
        .order_by(AuditLog.id.desc()).limit(1))
    assert state is None, f"无 endpoint 不得武装 low 状态，实际 {state}"
    # 注册 endpoint 后的下一个 tick 仍低位 → 正常预警
    status, payload, _ = _post_json(app, ids["key_b"], "/openapi/v1/webhooks",
                                    {"url": ids["hook_b_url"]})
    assert status == 200, payload
    _run_tick()
    assert len(_deliveries(session, ids["ent_b"], "balance.low")) == 1
    ent_b = session.get(Enterprise, ids["ent_b"])
    ent_b.usage_alert_threshold = None   # 收尾：停用 B 的扫描
    session.commit()
    print("  AC15 余额预警一次性·重武装·无 endpoint 不武装 ok")


def test_declaration_rate_limit(app, ids):
    """AC16：申报限流默认 60 次/小时/key，参保/停保共享 bucket，
    429 + Retry-After。（用环境变量把窗口额度缩小到 3 验证机制。）"""
    from backend.routers.openapi.declarations import declaration_rate_limit_per_hour

    assert declaration_rate_limit_per_hour() == 60, "默认额度必须是 60 次/小时"
    os.environ["OPENAPI_DECLARATION_RATE_LIMIT_PER_HOUR"] = "3"
    try:
        key = ids["key_rate"]
        for i, path in enumerate(["/openapi/v1/enrollments",
                                  "/openapi/v1/terminations",
                                  "/openapi/v1/enrollments"]):
            body = {"items": [{"name": "限流", "id_number": ID_B}]} \
                if "enroll" in path else {"items": [{"person_id": 999999}]}
            status, payload, _ = _post_json(app, key, path, body,
                                            idem=f"rate-{i}")
            assert status == 200, (i, payload)
        status, payload, headers = _post_json(
            app, key, "/openapi/v1/terminations",
            {"items": [{"person_id": 999999}]}, idem="rate-4")
        assert_unified_error(status, payload, 429, 42900)
        assert "retry-after" in headers, headers
        assert 0 < int(headers["retry-after"]) <= 3600, headers
    finally:
        del os.environ["OPENAPI_DECLARATION_RATE_LIMIT_PER_HOUR"]
    print("  AC16 申报限流共享 bucket + Retry-After ok")


# ------------------------------------------------------------------------ run

def run():
    with tempfile.TemporaryDirectory(prefix="xbb-openapi-write-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"   # 平台级限流放开
        # review-p2 M6：http loopback 回调默认被拒，测试显式开启豁免开关
        os.environ["OPENAPI_WEBHOOK_ALLOW_INSECURE"] = "1"

        test_migration_on_isolated_sqlite(folder)

        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core import storage
        from backend.core.db import SessionLocal
        from backend.models import (
            Enterprise, InsurerAccount, InsurerAccountLink, InsuredPerson,
            User, WorkPosition,
        )
        from backend.services.openapi.background import registered_job_names

        startup()
        for job in ("openapi_webhook_deliver", "openapi_balance_alerts",
                    "openapi_idempotency_cleanup"):
            assert job in registered_job_names(), registered_job_names()

        server = ThreadingHTTPServer(("127.0.0.1", 0), _Receiver)
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()

        try:
            with SessionLocal() as session:
                admin = session.scalar(select(User).where(User.username == "admin"))
                ent_a = session.scalar(select(Enterprise))
                ent_b = Enterprise(name="隔离企业B", kind="企业", contact="乙",
                                   phone="", status="active")
                ent_c = Enterprise(name="预警企业C", kind="企业", contact="丙",
                                   phone="", status="active")
                ent_poor = Enterprise(name="欠费企业D", kind="企业", contact="丁",
                                      phone="", status="active")
                session.add_all([ent_b, ent_c, ent_poor])
                session.commit()
                ids = {"ent_a": ent_a.id, "ent_b": ent_b.id,
                       "ent_c": ent_c.id, "ent_poor": ent_poor.id,
                       "admin": admin}

                # 资金：A/B 足额；poor 为 0（AC4c 负例）
                ent_a.usage_balance = 10000.0
                ent_a.usage_fee_daily = 0.1
                ent_b.usage_balance = 1000.0
                ent_poor.usage_balance = 0.0

                # 岗位（A 已审核；挂 plan 以便定价快照可用）与保司收款账户
                pos_a = WorkPosition(enterprise_id=ent_a.id, name="搬运工",
                                     occupation_class="3类", status="approved")
                session.add(pos_a)
                acct = InsurerAccount(label="平安收款账户", bank_name="X银行",
                                      account_no="1", account_holder="平台",
                                      status="active")
                session.add(acct)
                session.flush()
                session.add(InsurerAccountLink(insurer="平安", account_id=acct.id))
                ids["pos_a"] = pos_a.id

                # 预置：A 的在保人员（重复在保负例 + 停保申报对象）与 draft 人员
                p_dup = InsuredPerson(enterprise_id=ent_a.id, name="甲已在保",
                                      phone="13800000000", id_number=ID_DUP,
                                      status="active")
                p_draft = InsuredPerson(enterprise_id=ent_a.id, name="乙草稿",
                                        phone="", id_number=make_id(
                                            "11010119900101007"),
                                        status="draft")
                session.add_all([p_dup, p_draft])
                session.commit()
                ids["p_dup"], ids["p_draft"] = p_dup.id, p_draft.id

                write_scopes = ["declaration:write", "recharge:write",
                                "webhook:manage"]
                ids["key_a"] = _issue(session, admin, enterprise_id=ent_a.id,
                                      name="A 写入", scopes=write_scopes, env="prod")
                ids["key_b"] = _issue(session, admin, enterprise_id=ent_b.id,
                                      name="B 写入", scopes=write_scopes, env="prod")
                ids["key_c"] = _issue(session, admin, enterprise_id=ent_c.id,
                                      name="C webhook", scopes=["webhook:manage"],
                                      env="prod")
                ids["key_poor"] = _issue(session, admin, enterprise_id=ent_poor.id,
                                         name="D 欠费", scopes=["declaration:write"],
                                         env="prod")
                ids["key_rate"] = _issue(session, admin, enterprise_id=ent_b.id,
                                         name="B 限流", scopes=["declaration:write"],
                                         env="prod")
                ids["key_noscope"] = _issue(session, admin, enterprise_id=ent_a.id,
                                            name="A 无scope", scopes=["probe:read"],
                                            env="prod")

                # 本地接收端：A 与 C 各注册一个 endpoint（后续事件都会入 outbox）
                ids["hook_a_url"] = f"http://127.0.0.1:{port}/hook-a"
                ids["hook_b_url"] = f"http://127.0.0.1:{port}/hook-b"
                status, payload, _ = _post_json(app, ids["key_a"],
                                                "/openapi/v1/webhooks",
                                                {"url": ids["hook_a_url"]})
                assert status == 200, payload
                ids["ep_a"] = payload["data"]["id"]
                status, payload, _ = _post_json(
                    app, ids["key_c"], "/openapi/v1/webhooks",
                    {"url": f"http://127.0.0.1:{port}/hook-c"})
                assert status == 200, payload

                test_scope_negatives(app, ids)
                test_webhook_management(app, session, ids)
                test_idempotency(app, session, ids)
                test_idempotency_crash_window(app, session, ids)
                test_enrollment_declarations(app, session, ids)
                test_termination_declarations(app, session, ids)
                test_status_machine_and_events(app, session, ids)
                test_declaration_queries(app, ids)
                test_recharges(app, session, ids)
                test_webhook_delivery_and_signature(app, session, ids)
                test_backoff_and_resend(app, session, ids)
                test_usage_alert_threshold_admin_entry(session, ids)
                test_balance_alerts(app, session, ids)
                test_background_loop_nonblocking()
                test_declaration_rate_limit(app, ids)
                test_idempotency_cleanup(session)
        finally:
            server.shutdown()
            for url in _cleanup_urls:
                storage.delete(url)

    print("openapi write smoke passed")


if __name__ == "__main__":
    run()
