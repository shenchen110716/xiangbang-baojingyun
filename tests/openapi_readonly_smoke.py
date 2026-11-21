"""P1 开放平台只读面 smoke（story-p1-readonly AC2–AC15，入口 AC17）。

既有模式：tempfile 临时 SQLite、import backend 之前设 DATABASE_URL、自带
run()；HTTP 面复用 openapi_foundation_smoke 的进程内 ASGI 驱动器与 v2
签名助手（等价真实请求管线，无需起子进程）。

覆盖：签名正/负例、缺 scope 负例（AC14）、名单过滤/游标分页/状态语义/
脱敏与 read_sensitive 审计（AC2–AC4）、详情保障期与操作历史（AC5）、
跨租户越权负例六个面（AC6）、异步导出全链路与签名下载（AC7）、导出限流
（AC8）、账户口径勘误（AC9）、流水/发票（AC10/AC11）、保单与 download-url
（AC12/AC13）、独立 OpenAPI schema（AC15）、export_tasks 迁移（AC1 本地）。
"""
import asyncio
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(ROOT / "tests"))

os.environ.setdefault("ID_ENCRYPTION_KEY", "openapi-readonly-smoke-key")

from openapi_foundation_smoke import (  # noqa: E402  共用 ASGI 驱动与签名助手
    assert_unified_error, http_call, signed_call,
)

P0_HEAD = "a1f4e8c2d6b0"
P1_HEAD = "c7d2e9f4a1b8"

MASK_RE = re.compile(r"^\d{3}\*+\d{4}$")

_cleanup_urls: list[str] = []


def http_call_raw(app, method, path, *, query="", headers=None):
    """同 http_call 但返回原始字节 body（下载 XLSX/PDF 用）。"""
    scope = {
        "type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1", "method": method, "scheme": "http",
        "path": path, "raw_path": path.encode(), "query_string": query.encode(),
        "root_path": "",
        "headers": [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()],
        "client": ("127.0.0.1", 40000), "server": ("testserver", 80),
    }
    messages = []
    state = {"sent": False}

    async def receive():
        if not state["sent"]:
            state["sent"] = True
            return {"type": "http.request", "body": b"", "more_body": False}
        return {"type": "http.disconnect"}

    async def send(message):
        messages.append(message)

    asyncio.run(app(scope, receive, send))
    start = next(m for m in messages if m["type"] == "http.response.start")
    raw = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
    return start["status"], raw


def _issue(session, admin, **kwargs):
    from backend.routers.openapi_admin import OpenApiClientIssueIn, issue_openapi_client
    return issue_openapi_client(OpenApiClientIssueIn(**kwargs), user=admin, session=session)


def _get(app, key, path, query=""):
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "GET", path, query=query)
    return status, payload, headers


def _post(app, key, path, body=b""):
    (status, payload, headers), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", path, body=body)
    return status, payload, headers


# ---------------------------------------------------------------- 数据工场

def seed_business_data(session, ids):
    from backend.core import storage
    from backend.models import (
        Enterprise, EnterprisePremiumAccount, InsurancePlan, InsuredPerson,
        InsurerAccount, InsurerAccountLink, Invoice, LedgerEntry,
        ParticipationOperation, Policy, PolicyMember, WorkPosition,
    )

    ent_a, ent_b = ids["ent_a"], ids["ent_b"]
    now = datetime.now(timezone.utc)
    naive_now = now.replace(tzinfo=None)

    plan = InsurancePlan(insurer="平安", name="雇主险A", price=10)
    session.add(plan); session.flush()
    pos_a = WorkPosition(enterprise_id=ent_a, name="搬运工", occupation_class="3类")
    pos_a2 = WorkPosition(enterprise_id=ent_a, name="司机", occupation_class="4类")
    session.add_all([pos_a, pos_a2]); session.flush()
    ids["pos_a"] = pos_a.id

    def person(ent, name, idn, status, position=None):
        row = InsuredPerson(enterprise_id=ent, name=name, phone="13800000000",
                            id_number=idn, status=status,
                            position_id=position)
        session.add(row); session.flush()
        return row

    # 企业 A：四种状态 + 分页用
    p_draft = person(ent_a, "甲草稿", "110101199001011234", "draft", pos_a.id)
    p_pending = person(ent_a, "乙待生效", "110101199001022345", "pending", pos_a.id)
    p_active = person(ent_a, "丙在保", "110101199001033456", "active", pos_a2.id)
    p_stopped = person(ent_a, "丁已停保", "110101199001044567", "stopped", pos_a2.id)
    p_extra1 = person(ent_a, "戊", "110101199001055678", "active", pos_a.id)
    p_extra2 = person(ent_a, "己", "110101199001066789", "active", pos_a.id)
    # XLSX 公式注入负样本（review-p1 m1）：姓名以 = 开头
    p_inject = person(ent_a, '=HYPERLINK("http://evil","x")',
                      "110101199001077890", "draft")
    # 企业 B：越权负例
    p_b = person(ent_b, "B企业员工", "220202199102021111", "active")

    policy_a = Policy(policy_no="POL-A-001", enterprise_id=ent_a, plan_id=plan.id,
                      status="active", start_date="2026-01-01", end_date="2026-12-31")
    policy_a2 = Policy(policy_no="POL-A-002", enterprise_id=ent_a, plan_id=plan.id,
                       status="active")  # 无保单文件（AC12 42200 负例）
    policy_b = Policy(policy_no="POL-B-001", enterprise_id=ent_b, plan_id=plan.id,
                      status="active")
    session.add_all([policy_a, policy_a2, policy_b]); session.flush()

    # 真实保单文件（本地存储，测试尾部清理）
    policy_a.document_url = storage.save_bytes(
        f"openapi-tests/{policy_a.id}-doc.pdf", b"%PDF-1.4 openapi readonly smoke")
    policy_a.document_name = "POL-A-001.pdf"
    _cleanup_urls.append(policy_a.document_url)

    session.add_all([
        PolicyMember(policy_id=policy_a.id, person_id=p_active.id,
                     effective_at=naive_now - timedelta(days=30), status="active"),
        PolicyMember(policy_id=policy_a.id, person_id=p_stopped.id,
                     effective_at=naive_now - timedelta(days=60),
                     terminated_at=naive_now - timedelta(days=10), status="terminated"),
        PolicyMember(policy_id=policy_b.id, person_id=p_b.id,
                     effective_at=naive_now - timedelta(days=5), status="active"),
    ])
    session.add_all([
        ParticipationOperation(enterprise_id=ent_a, person_id=p_active.id,
                               operation_type="enrollment",
                               submitted_at=now - timedelta(days=30),
                               expected_at=now - timedelta(days=29),
                               insurer_confirmed_at=now - timedelta(days=29)),
        ParticipationOperation(enterprise_id=ent_a, person_id=p_stopped.id,
                               operation_type="enrollment",
                               submitted_at=now - timedelta(days=60)),
        ParticipationOperation(enterprise_id=ent_a, person_id=p_stopped.id,
                               operation_type="termination",
                               submitted_at=now - timedelta(days=10)),
        ParticipationOperation(enterprise_id=ent_b, person_id=p_b.id,
                               operation_type="enrollment",
                               submitted_at=now - timedelta(days=5)),
    ])

    # 分账户保费余额 + 服务费（勘误 1 口径）
    acct = InsurerAccount(label="平安收款账户", bank_name="X银行", account_no="1",
                          account_holder="平台", status="active")
    session.add(acct); session.flush()
    session.add(InsurerAccountLink(insurer="平安", account_id=acct.id))
    session.add(EnterprisePremiumAccount(enterprise_id=ent_a, account_id=acct.id,
                                         balance=500.0))
    session.add(EnterprisePremiumAccount(enterprise_id=ent_b, account_id=acct.id,
                                         balance=66.0))
    ent_a_row = session.get(Enterprise, ent_a)
    ent_a_row.usage_balance = 1000.0
    ent_a_row.usage_fee_daily = 0.1

    # review-p1 M1 小时级边界样本：北京 bj_day 04:00 == UTC (bj_day-1) 20:00；
    # occurred_at 列按真实写入路径存 UTC naive。
    from backend.core.business_time import BUSINESS_TIMEZONE
    bj_day = (now.astimezone(BUSINESS_TIMEZONE) - timedelta(days=5)).date()
    boundary_bj = datetime(bj_day.year, bj_day.month, bj_day.day, 4, 0, 0,
                           tzinfo=BUSINESS_TIMEZONE)
    boundary_utc_naive = boundary_bj.astimezone(timezone.utc).replace(tzinfo=None)
    ids["bj_day"] = bj_day.isoformat()
    session.add_all([
        LedgerEntry(enterprise_id=ent_a, account="premium", direction="credit",
                    amount=500, business_type="recharge", account_id=acct.id,
                    occurred_at=naive_now - timedelta(days=3)),
        LedgerEntry(enterprise_id=ent_a, account="usage", direction="credit",
                    amount=1000, business_type="recharge",
                    occurred_at=naive_now - timedelta(days=2)),
        LedgerEntry(enterprise_id=ent_a, account="usage", direction="debit",
                    amount=7, business_type="boundary_probe",
                    occurred_at=boundary_utc_naive),
        LedgerEntry(enterprise_id=ent_a, account="premium", direction="debit",
                    amount=30, business_type="premium_deduction",
                    account_id=acct.id, occurred_at=naive_now - timedelta(days=400)),
        LedgerEntry(enterprise_id=ent_b, account="premium", direction="credit",
                    amount=66, business_type="recharge", account_id=acct.id,
                    occurred_at=naive_now - timedelta(days=1)),
    ])
    session.add_all([
        Invoice(enterprise_id=ent_a, account="premium", amount=500,
                title="A公司", status="pending"),
        Invoice(enterprise_id=ent_b, account="premium", amount=66,
                title="B公司", status="pending"),
    ])
    session.commit()

    ids.update({
        "p_draft": p_draft.id, "p_pending": p_pending.id,
        "p_active": p_active.id, "p_stopped": p_stopped.id,
        "p_extra1": p_extra1.id, "p_extra2": p_extra2.id,
        "p_inject": p_inject.id, "p_b": p_b.id,
        "policy_a": policy_a.id, "policy_a2": policy_a2.id,
        "policy_b": policy_b.id, "plan": plan.id, "acct": acct.id,
    })
    ids["a_person_ids"] = {p_draft.id, p_pending.id, p_active.id,
                           p_stopped.id, p_extra1.id, p_extra2.id,
                           p_inject.id}


# ---------------------------------------------------------------- 各 AC 场景

def test_signature_and_scope(app, ids):
    """签名正/负例 + AC14 缺 scope 负例（五个端点全 403/40300）。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons")
    assert status == 200 and payload["code"] == 0, payload

    (status, payload, _), _ = signed_call(
        app, "wrong-secret", key["key_id"], "GET", "/openapi/v1/insured-persons")
    assert_unified_error(status, payload, 401, 40100)

    noscope = ids["key_noscope"]
    for method, path in [("GET", "/openapi/v1/insured-persons"),
                         ("POST", "/openapi/v1/insured-persons/export"),
                         ("GET", "/openapi/v1/policies"),
                         ("GET", "/openapi/v1/accounts"),
                         ("GET", "/openapi/v1/ledger-entries"),
                         ("GET", "/openapi/v1/invoices")]:
        (status, payload, _), _ = signed_call(
            app, noscope["secret"], noscope["key_id"], method, path)
        assert_unified_error(status, payload, 403, 40300)
    print("  签名正负例 + AC14 缺 scope 负例 ok")


def test_insured_list(app, session, ids):
    """AC2 过滤与游标分页 + AC3 状态语义 + AC4 默认脱敏。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons")
    assert status == 200, payload
    items = payload["data"]["items"]
    assert {i["id"] for i in items} == ids["a_person_ids"], items
    by_id = {i["id"]: i for i in items}
    # AC3 内部码 + 对外语义
    for pid, status_pair in [(ids["p_draft"], ("draft", "未参保")),
                             (ids["p_pending"], ("pending", "待生效")),
                             (ids["p_active"], ("active", "在保")),
                             (ids["p_stopped"], ("stopped", "已停保"))]:
        assert (by_id[pid]["status"], by_id[pid]["status_label"]) == status_pair, by_id[pid]
    # AC4 默认脱敏：前3 + * + 后4
    row = by_id[ids["p_draft"]]
    assert row["id_number"] == "110***********1234", row
    assert all(MASK_RE.fullmatch(i["id_number"]) for i in items), items

    # status / position_id 过滤
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query="status=active")
    got = {i["id"] for i in payload["data"]["items"]}
    assert got == {ids["p_active"], ids["p_extra1"], ids["p_extra2"]}, got
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query=f"position_id={ids['pos_a']}&status=active")
    got = {i["id"] for i in payload["data"]["items"]}
    assert got == {ids["p_extra1"], ids["p_extra2"]}, got
    # 非法 status → 42200
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query="status=bogus")
    assert_unified_error(status, payload, 422, 42200)

    # 游标分页：limit=2 逐页走完，无重复无遗漏，页间 id 递减
    seen, cursor, pages = [], None, 0
    while True:
        query = "limit=2" + (f"&cursor={cursor}" if cursor else "")
        status, payload, _ = _get(app, key, "/openapi/v1/insured-persons", query=query)
        assert status == 200, payload
        page = payload["data"]["items"]
        assert len(page) <= 2
        seen.extend(i["id"] for i in page)
        pages += 1
        cursor = payload["data"]["next_cursor"]
        if not cursor:
            break
        assert pages < 10, "分页死循环"
    assert sorted(seen, reverse=True) == seen, f"应按 id 倒序：{seen}"
    assert set(seen) == ids["a_person_ids"] and len(seen) == len(ids["a_person_ids"])
    # 非法游标 → 42200
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query="cursor=abc")
    assert_unified_error(status, payload, 422, 42200)

    # updated_since：过去时间命中全部、未来时间命中 0；参停保操作也算更新。
    # 时间戳用 Z 后缀：+00:00 的 + 在 query string 里会被解成空格。
    def _iso_z(dt):
        return dt.strftime("%Y-%m-%dT%H:%M:%SZ")

    past = _iso_z(datetime.now(timezone.utc) - timedelta(days=365))
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query=f"updated_since={past}")
    assert {i["id"] for i in payload["data"]["items"]} == ids["a_person_ids"]
    future = _iso_z(datetime.now(timezone.utc) + timedelta(hours=1))
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query=f"updated_since={future}")
    assert payload["data"]["items"] == [], payload["data"]
    # p_active 有 30 天前的参保操作：卡在 31 天前应命中它
    recentish = _iso_z(datetime.now(timezone.utc) - timedelta(days=31))
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query=f"updated_since={recentish}")
    got = {i["id"] for i in payload["data"]["items"]}
    assert ids["p_active"] in got, got
    status, payload, _ = _get(app, key, "/openapi/v1/insured-persons",
                              query="updated_since=not-a-date")
    assert_unified_error(status, payload, 422, 42200)
    print("  AC2/AC3/AC4 名单过滤·分页·语义·脱敏 ok")


def test_sensitive_scope_and_audit(app, session, ids):
    """AC4：read_sensitive 同端点回明文且写审计；默认 key 全出口无明文。"""
    from sqlalchemy import select
    from backend.models import AuditLog

    sens = ids["key_sens"]
    status, payload, _ = _get(app, sens,
                              f"/openapi/v1/insured-persons/{ids['p_draft']}")
    assert status == 200 and payload["data"]["id_number"] == "110101199001011234", payload
    audit_row = session.scalar(
        select(AuditLog).where(AuditLog.action == "openapi_read_sensitive")
        .order_by(AuditLog.id.desc()))
    assert audit_row is not None and sens["key_id"] in audit_row.detail, \
        "明文证件号出口必须写 audit_logs 且带 key_id"
    assert audit_row.user_id is None

    status, payload, _ = _get(app, sens, "/openapi/v1/insured-persons")
    assert all(not MASK_RE.fullmatch(i["id_number"])
               for i in payload["data"]["items"]), "sensitive key 列表应为明文"

    # 普通 key 在任何响应中都不出现明文
    key = ids["key_a"]
    status, payload, _ = _get(app, key,
                              f"/openapi/v1/insured-persons/{ids['p_draft']}")
    assert "110101199001011234" not in json.dumps(payload, ensure_ascii=False)
    print("  AC4 read_sensitive 明文 + 审计 ok")


def test_detail_and_history(app, ids):
    """AC5：详情含当前保障期间；history 返回参停保时间线。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key,
                              f"/openapi/v1/insured-persons/{ids['p_active']}")
    assert status == 200, payload
    coverage = payload["data"]["coverage"]
    assert coverage and coverage["policy_no"] == "POL-A-001", coverage
    assert coverage["effective_at"] and coverage["status"] == "active", coverage
    assert coverage["terminated_at"] is None, coverage

    status, payload, _ = _get(
        app, key, f"/openapi/v1/insured-persons/{ids['p_stopped']}/history")
    assert status == 200, payload
    ops = payload["data"]["items"]
    assert [o["operation_type"] for o in ops] == ["termination", "enrollment"], ops
    assert all("submitted_at" in o and "expected_at" in o
               and "insurer_confirmed_at" in o for o in ops), ops
    # 无保障期的人 coverage 为 null
    status, payload, _ = _get(app, key,
                              f"/openapi/v1/insured-persons/{ids['p_draft']}")
    assert payload["data"]["coverage"] is None, payload["data"]
    print("  AC5 详情保障期 + 操作历史 ok")


def test_cross_tenant_isolation(app, session, ids):
    """AC6：企业 B 的 key 对企业 A 的六个面全部拿不到，404 模糊。"""
    key_b = ids["key_b"]
    # 人员详情/历史
    for path in [f"/openapi/v1/insured-persons/{ids['p_active']}",
                 f"/openapi/v1/insured-persons/{ids['p_active']}/history"]:
        status, payload, _ = _get(app, key_b, path)
        assert_unified_error(status, payload, 404, 42200)
    # 列表不含 A 的数据
    status, payload, _ = _get(app, key_b, "/openapi/v1/insured-persons")
    assert {i["id"] for i in payload["data"]["items"]} == {ids["p_b"]}, payload["data"]
    # 保单详情/文件/成员
    for method, path in [("GET", f"/openapi/v1/policies/{ids['policy_a']}"),
                         ("GET", f"/openapi/v1/policies/{ids['policy_a']}/members"),
                         ("POST", f"/openapi/v1/policies/{ids['policy_a']}/download-url")]:
        (status, payload, _), _ = signed_call(
            app, key_b["secret"], key_b["key_id"], method, path)
        assert_unified_error(status, payload, 404, 42200)
    status, payload, _ = _get(app, key_b, "/openapi/v1/policies")
    assert {i["id"] for i in payload["data"]["items"]} == {ids["policy_b"]}, payload["data"]
    # 账户/流水/发票只见自己
    status, payload, _ = _get(app, key_b, "/openapi/v1/accounts")
    assert payload["data"]["premium_accounts"][0]["balance"] == 66.0, payload["data"]
    status, payload, _ = _get(app, key_b, "/openapi/v1/ledger-entries")
    entries = payload["data"]["items"]
    assert len(entries) == 1 and entries[0]["amount"] == 66.0, entries
    status, payload, _ = _get(app, key_b, "/openapi/v1/invoices")
    invoices = payload["data"]["items"]
    assert len(invoices) == 1 and invoices[0]["title"] == "B公司", invoices
    # 导出任务：B 查 A 的 task 404（export 面）
    if "task_a" in ids:
        status, payload, _ = _get(app, key_b,
                                  f"/openapi/v1/export-tasks/{ids['task_a']}")
        assert_unified_error(status, payload, 404, 42200)
    print("  AC6 跨租户六个面越权负例 ok")


def _run_tick():
    from backend.core.db import SessionLocal
    from backend.services.openapi.background import run_tick_once
    with SessionLocal() as session:
        return run_tick_once(session)


def _export_chain(app, key, expect_plain: bool, ids):
    import openpyxl

    status, payload, _ = _post(app, key, "/openapi/v1/insured-persons/export")
    assert status == 200, payload
    task_id = payload["data"]["task_id"]
    assert payload["data"]["status"] == "pending", payload

    ran = _run_tick()
    assert "run_openapi_export_tasks" in ran, ran

    status, payload, _ = _get(app, key, f"/openapi/v1/export-tasks/{task_id}")
    assert status == 200 and payload["data"]["status"] == "done", payload
    url = payload["data"]["download_url"]
    assert url and url.startswith("/openapi/v1/export-files/"), payload["data"]
    path, _, query = url.partition("?")
    dl_status, raw = http_call_raw(app, "GET", path, query=query)
    assert dl_status == 200 and raw[:2] == b"PK", (dl_status, raw[:20])

    book = openpyxl.load_workbook(BytesIO(raw))
    sheet = book.active
    rows = list(sheet.iter_rows(min_row=2, values_only=True))
    id_numbers = [str(r[3]) for r in rows]
    names = [str(r[1]) for r in rows]
    row_ids = {r[0] for r in rows}
    return task_id, url, id_numbers, row_ids, names


def test_export_full_chain(app, session, ids):
    """AC7：导出任务全链路 + 文件按 AC4 同规则脱敏 + 签名链接过期拒绝。"""
    from sqlalchemy import select

    from backend.core.file_tokens import make_download_token
    from backend.models import AuditLog

    task_id, url, id_numbers, row_ids, names = _export_chain(
        app, ids["key_a"], expect_plain=False, ids=ids)
    ids["task_a"] = task_id
    ids["task_a_url"] = url
    assert row_ids == ids["a_person_ids"], f"导出必须且只含本企业人员：{row_ids}"
    assert all(MASK_RE.fullmatch(n) for n in id_numbers), id_numbers
    # review-p1 m1：= 开头的姓名必须被 ' 前缀转义成文本，不得按公式落盘
    assert any(n.startswith("'=HYPERLINK") for n in names), names
    assert not any(n.startswith("=") for n in names), names

    # read_sensitive key 的导出文件为明文（与 API 同口径）
    task_sens, _, plain_numbers, _, _ = _export_chain(
        app, ids["key_sens"], expect_plain=True, ids=ids)
    assert "110101199001011234" in plain_numbers, plain_numbers

    # review-p1 M2①：明文口径任务，无 read_sensitive 的 key 连任务状态
    # （含换新下载链接）都拿不到——降权后旧明文文件立即不可续取。
    status, payload, _ = _get(app, ids["key_a"],
                              f"/openapi/v1/export-tasks/{task_sens}")
    assert_unified_error(status, payload, 403, 40300)
    # review-p1 M2②：每次发放明文文件链接都写审计
    session.expire_all()
    details = [row.detail or "" for row in session.scalars(
        select(AuditLog).where(AuditLog.action == "openapi_read_sensitive"))]
    assert any(f"export-download-url task={task_sens}" in d for d in details), details

    # 过期签名链接拒绝
    token, expires = make_download_token(f"openapi-export:{task_id}", -10)
    status, payload, _ = http_call(
        app, "GET", f"/openapi/v1/export-files/{task_id}",
        query=f"token={token}&expires={expires}")
    assert_unified_error(status, payload, 403, 40300)
    # 篡改 token 拒绝
    path, _, query = url.partition("?")
    status, payload, _ = http_call(app, "GET", path,
                                   query=query.replace("token=", "token=00"))
    assert_unified_error(status, payload, 403, 40300)

    # 企业 B 的导出只含 B 的人（AC6 导出面）
    _, _, _, b_rows, _ = _export_chain(app, ids["key_b"], expect_plain=False, ids=ids)
    assert b_rows == {ids["p_b"]}, b_rows

    # 登记清理
    from backend.models import ExportTask
    for task in session.query(ExportTask).all():
        if task.file_url:
            _cleanup_urls.append(task.file_url)
    print("  AC7 导出全链路·脱敏·签名下载 ok")


def test_export_stuck_recovery(app, session, ids):
    """review-p1 M3：卡 processing 超 15 分钟的任务每 tick 回收——第一次
    重置 pending 重跑（同一 tick 内完成），已重试过仍超时则置 failed。"""
    from backend.models import ExportTask
    from backend.services.openapi.export_tasks import (
        TIMEOUT_FAILED_MESSAGE, TIMEOUT_RETRY_MARKER,
    )

    stale = datetime.now(timezone.utc) - timedelta(minutes=20)
    first = ExportTask(enterprise_id=ids["ent_a"], key_id=ids["key_a"]["key_id"],
                       task_type="insured_persons",
                       params_json='{"sensitive": false}',
                       status="processing", created_at=stale)
    second = ExportTask(enterprise_id=ids["ent_a"], key_id=ids["key_a"]["key_id"],
                        task_type="insured_persons",
                        params_json='{"sensitive": false}',
                        status="processing", created_at=stale,
                        error=TIMEOUT_RETRY_MARKER)
    session.add_all([first, second]); session.commit()
    first_id, second_id = first.id, second.id

    _run_tick()
    session.expire_all()
    first = session.get(ExportTask, first_id)
    second = session.get(ExportTask, second_id)
    # 第一次超时：重置 pending 后同一 tick 重跑成功，内部标记不残留
    assert first.status == "done" and first.file_url, (first.status, first.error)
    assert first.error == "", first.error
    _cleanup_urls.append(first.file_url)
    # 已重试过仍超时：置 failed + finished_at，对外是明确的超时文案
    assert second.status == "failed" and second.finished_at is not None, second.status
    assert second.error == TIMEOUT_FAILED_MESSAGE, second.error
    status, payload, _ = _get(app, ids["key_a"],
                              f"/openapi/v1/export-tasks/{second_id}")
    assert payload["data"]["status"] == "failed", payload["data"]
    assert payload["data"]["error"] == TIMEOUT_FAILED_MESSAGE, payload["data"]
    print("  M3 卡 processing 回收：重试一次后 failed ok")


def test_export_retention_cleanup(app, session, ids):
    """review-p1 M2③：done 任务超过 48h 留存期 → 删落盘文件、任务置
    expired、旧签名链接失效。"""
    from backend.core import storage
    from backend.models import ExportTask
    from backend.services.openapi.export_tasks import EXPIRED_MESSAGE

    task = session.get(ExportTask, ids["task_a"])
    old_file = task.file_url
    assert task.status == "done" and old_file, (task.status, old_file)
    task.finished_at = datetime.now(timezone.utc) - timedelta(hours=49)
    session.commit()

    ran = _run_tick()
    assert "cleanup_openapi_export_tasks" in ran, ran
    session.expire_all()

    status, payload, _ = _get(app, ids["key_a"],
                              f"/openapi/v1/export-tasks/{ids['task_a']}")
    data = payload["data"]
    assert data["status"] == "expired", data
    assert data["download_url"] is None, data
    assert data["error"] == EXPIRED_MESSAGE, data

    task = session.get(ExportTask, ids["task_a"])
    assert task.status == "expired" and task.file_url == "", (task.status, task.file_url)
    assert storage.resolve(old_file) is None, "落盘明文文件必须已删除"
    # 旧签名 URL（token 未过期）兑付失败：状态非 done、文件已删
    path, _, query = ids["task_a_url"].partition("?")
    dl_status, payload, _ = http_call(app, "GET", path, query=query)
    assert_unified_error(dl_status, payload, 404, 42200)
    print("  M2③ 48h 留存清理：任务置 expired、文件删除、旧链接失效 ok")


def test_export_rate_limit(app, ids):
    """AC8：导出独立限流 10 次/小时/key，超限 429 + 42900 + Retry-After。"""
    key = ids["key_rate"]
    for i in range(10):
        status, payload, _ = _post(app, key, "/openapi/v1/insured-persons/export")
        assert status == 200, f"第 {i+1} 次导出应成功：{payload}"
    status, payload, headers = _post(app, key, "/openapi/v1/insured-persons/export")
    assert_unified_error(status, payload, 429, 42900)
    assert "retry-after" in headers, headers
    print("  AC8 导出限流 10 次/小时 ok")


def test_accounts_errata(app, session, ids):
    """AC9 勘误 1：返回 = 分账户列表 + usage_account_view 直算值。"""
    from backend.models import Enterprise
    from backend.services.accruals import usage_account_view
    from backend.services.recharge import premium_accounts_for_enterprise

    status, payload, _ = _get(app, ids["key_a"], "/openapi/v1/accounts")
    assert status == 200, payload
    data = payload["data"]
    expect_premium = premium_accounts_for_enterprise(session, ids["ent_a"])
    expect_usage = usage_account_view(session, session.get(Enterprise, ids["ent_a"]))
    assert data["premium_accounts"] == expect_premium, (data, expect_premium)
    assert data["usage"] == expect_usage, (data, expect_usage)
    assert data["premium_accounts"][0]["balance"] == 500.0
    # 不是 Enterprise.usage_balance 原始列值（可用额 = 充值 − 消耗）
    assert data["usage"]["recharged"] == 1000.0
    assert data["usage"]["available"] == expect_usage["available"]
    assert "usage_fee_daily" not in data  # 字段名为 daily_rate（view 口径）
    print("  AC9 账户口径勘误 ok")


def test_ledger_and_invoices(app, ids):
    """AC10 流水过滤 + AC11 发票。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key, "/openapi/v1/ledger-entries")
    all_entries = payload["data"]["items"]
    assert len(all_entries) == 4, all_entries
    assert {e["direction"] for e in all_entries} == {"credit", "debit"}
    # review-p1 M1 同根因：occurred_at 输出必须带 UTC 偏移量
    assert all(e["occurred_at"].endswith("+00:00") for e in all_entries), all_entries
    status, payload, _ = _get(app, key, "/openapi/v1/ledger-entries",
                              query="account=premium")
    assert {e["account"] for e in payload["data"]["items"]} == {"premium"}
    assert len(payload["data"]["items"]) == 2
    # from/to 时间窗（近 7 天）：排除 400 天前那笔
    frm = (datetime.now(timezone.utc) - timedelta(days=7)).strftime("%Y-%m-%d")
    status, payload, _ = _get(app, key, "/openapi/v1/ledger-entries",
                              query=f"account=premium&from={frm}")
    items = payload["data"]["items"]
    assert len(items) == 1 and items[0]["business_type"] == "recharge", items
    status, payload, _ = _get(app, key, "/openapi/v1/ledger-entries",
                              query="account=bogus")
    assert_unified_error(status, payload, 422, 42200)

    # review-p1 M1：小时级时区边界。样本流水发生在北京 bj_day 04:00
    # （= UTC 前一日 20:00）。裸 from/to 按北京时间口径解析后折 UTC 比较：
    # 北京当日 [00:00, 05:00] 必须命中，从 05:00 起必须排除——
    # 修复前（北京 naive 直接对 UTC naive 比）这两个断言各差 8 小时。
    bj_day = ids["bj_day"]
    status, payload, _ = _get(
        app, key, "/openapi/v1/ledger-entries",
        query=f"account=usage&from={bj_day}T00:00:00&to={bj_day}T05:00:00")
    items = payload["data"]["items"]
    assert [e["business_type"] for e in items] == ["boundary_probe"], items
    status, payload, _ = _get(
        app, key, "/openapi/v1/ledger-entries",
        query=f"account=usage&from={bj_day}T05:00:00&to={bj_day}T23:59:59")
    assert payload["data"]["items"] == [], payload["data"]
    # 带时区输入按其自身偏移：UTC 前一日 19:00Z 起同样命中
    prev_utc = (datetime.fromisoformat(bj_day) - timedelta(days=1)).date()
    status, payload, _ = _get(
        app, key, "/openapi/v1/ledger-entries",
        query=f"account=usage&from={prev_utc}T19:00:00Z&to={prev_utc}T21:00:00Z")
    items = payload["data"]["items"]
    assert [e["business_type"] for e in items] == ["boundary_probe"], items

    status, payload, _ = _get(app, key, "/openapi/v1/invoices")
    invoices = payload["data"]["items"]
    assert len(invoices) == 1 and invoices[0]["title"] == "A公司", invoices
    assert invoices[0]["amount"] == 500.0
    print("  AC10/AC11 流水过滤 + 发票 ok")


def test_policies(app, ids):
    """AC12 保单列表/详情/download-url + AC13 成员脱敏。"""
    key = ids["key_a"]
    status, payload, _ = _get(app, key, "/openapi/v1/policies")
    items = payload["data"]["items"]
    assert {i["id"] for i in items} == {ids["policy_a"], ids["policy_a2"]}, items
    # 不泄内部定价字段
    forbidden = {"premium", "commission_rate", "profit_amount", "settle_price"}
    assert not (set(items[0]) & forbidden), items[0]

    status, payload, _ = _get(app, key, f"/openapi/v1/policies/{ids['policy_a']}")
    assert payload["data"]["policy_no"] == "POL-A-001"
    assert payload["data"]["has_document"] is True

    # download-url：300s 签名 URL，可下载；无文件保单 42200
    status, payload, _ = _post(app, key,
                               f"/openapi/v1/policies/{ids['policy_a']}/download-url")
    assert status == 200, payload
    url = payload["data"]["download_url"]
    expires_at = payload["data"]["expires_at"]
    assert 0 < expires_at - int(time.time()) <= 300, "签名 URL TTL 必须 ≤300s"
    path, _, query = url.partition("?")
    dl_status, raw = http_call_raw(app, "GET", path, query=query)
    assert dl_status == 200 and raw.startswith(b"%PDF"), (dl_status, raw[:20])
    # 过期链接拒绝
    from backend.core.file_tokens import make_download_token
    token, expires = make_download_token(
        f"openapi-policy-document:{ids['policy_a']}", -10)
    status, payload, _ = http_call(
        app, "GET", f"/openapi/v1/policy-files/{ids['policy_a']}",
        query=f"token={token}&expires={expires}")
    assert_unified_error(status, payload, 403, 40300)
    status, payload, _ = _post(
        app, key, f"/openapi/v1/policies/{ids['policy_a2']}/download-url")
    assert_unified_error(status, payload, 422, 42200)

    # AC13 成员：仅本企业、默认脱敏
    status, payload, _ = _get(app, key,
                              f"/openapi/v1/policies/{ids['policy_a']}/members")
    members = payload["data"]["items"]
    assert {m["person_id"] for m in members} == {ids["p_active"], ids["p_stopped"]}, members
    assert all(MASK_RE.fullmatch(m["id_number"]) for m in members), members
    # sensitive key 成员明文
    status, payload, _ = _get(app, ids["key_sens"],
                              f"/openapi/v1/policies/{ids['policy_a']}/members")
    assert any(m["id_number"] == "110101199001033456"
               for m in payload["data"]["items"]), payload["data"]
    print("  AC12/AC13 保单·下载链接·成员 ok")


def test_schema_separation(app):
    """AC15：/openapi/v1/openapi.json 只含开放面；主 schema 不含开放面。"""
    status, schema, _ = http_call(app, "GET", "/openapi/v1/openapi.json")
    assert status == 200 and schema.get("openapi"), schema if isinstance(schema, dict) else schema
    paths = schema["paths"]
    assert paths and all(p.startswith("/openapi/v1") for p in paths), sorted(paths)
    assert "/openapi/v1/insured-persons" in paths and "/openapi/v1/accounts" in paths

    main = app.openapi()
    assert not any(p.startswith("/openapi/v1") for p in main["paths"]), \
        "主 /docs schema 不得包含开放平台路由"
    assert any(p.startswith("/api") for p in main["paths"])
    print("  AC15 独立 OpenAPI schema ok")


def test_migration_on_isolated_sqlite(folder):
    """AC1 本地部分：stamp P0 head 后 upgrade 建出 export_tasks；单一 head。"""
    import sqlite3

    mig_db = Path(folder) / "mig.db"
    with sqlite3.connect(mig_db) as conn:
        conn.execute("CREATE TABLE enterprises (id INTEGER PRIMARY KEY)")

    env = {**os.environ, "DATABASE_URL": f"sqlite:///{mig_db}"}
    for args in (["stamp", P0_HEAD], ["upgrade", "head"]):
        proc = subprocess.run([sys.executable, "-m", "alembic", *args],
                              cwd=ROOT, env=env, capture_output=True, text=True)
        assert proc.returncode == 0, f"alembic {args} failed:\n{proc.stdout}\n{proc.stderr}"
    heads = subprocess.run([sys.executable, "-m", "alembic", "heads"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
    head_lines = [l for l in heads.stdout.splitlines() if l.strip()]
    assert len(head_lines) == 1 and P1_HEAD in head_lines[0], heads.stdout

    with sqlite3.connect(mig_db) as conn:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(export_tasks)")}
        for col in ("id", "enterprise_id", "key_id", "task_type", "params_json",
                    "status", "file_url", "error", "created_at", "finished_at"):
            assert col in cols, f"export_tasks.{col} missing: {cols}"
        indexes = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        assert "ix_export_tasks_status" in indexes, indexes
    print("  AC1(本地) export_tasks 迁移在隔离 SQLite 走通 ok")


# ------------------------------------------------------------------------ run

def run():
    with tempfile.TemporaryDirectory(prefix="xbb-openapi-readonly-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"   # 平台级限流放开

        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core import storage
        from backend.core.db import SessionLocal
        from backend.models import Enterprise, User
        from backend.services.openapi.background import registered_job_names

        startup()
        assert "run_openapi_export_tasks" in registered_job_names(), \
            registered_job_names()
        # review-p1 M2③：留存清理任务必须挂上后台循环
        assert "cleanup_openapi_export_tasks" in registered_job_names(), \
            registered_job_names()

        try:
            with SessionLocal() as session:
                admin = session.scalar(select(User).where(User.username == "admin"))
                ent_a = session.scalar(select(Enterprise))
                ent_b = Enterprise(name="隔离企业B", kind="企业", contact="乙",
                                   phone="", status="active")
                session.add(ent_b); session.commit()
                ids = {"ent_a": ent_a.id, "ent_b": ent_b.id}

                read_scopes = ["insured:read", "policy:read", "account:read"]

                def issue(name, ent, scopes):
                    out = _issue(session, admin, enterprise_id=ent, name=name,
                                 scopes=scopes, env="prod")
                    return {"key_id": out["key_id"], "secret": out["secret"]}

                ids["key_a"] = issue("A 只读", ent_a.id, read_scopes)
                ids["key_sens"] = issue("A 含明文", ent_a.id,
                                        read_scopes + ["insured:read_sensitive"])
                ids["key_noscope"] = issue("A 无scope", ent_a.id, ["probe:read"])
                ids["key_b"] = issue("B 只读", ent_b.id, read_scopes)
                ids["key_rate"] = issue("B 限流用", ent_b.id, ["insured:read"])

                seed_business_data(session, ids)

                test_signature_and_scope(app, ids)
                test_insured_list(app, session, ids)
                test_sensitive_scope_and_audit(app, session, ids)
                test_detail_and_history(app, ids)
                test_export_full_chain(app, session, ids)
                test_cross_tenant_isolation(app, session, ids)
                test_export_stuck_recovery(app, session, ids)
                test_export_retention_cleanup(app, session, ids)
                test_export_rate_limit(app, ids)
                test_accounts_errata(app, session, ids)
                test_ledger_and_invoices(app, ids)
                test_policies(app, ids)

            test_schema_separation(app)
            test_migration_on_isolated_sqlite(folder)
        finally:
            for url in _cleanup_urls:
                storage.delete(url)

    print("openapi readonly smoke passed")


if __name__ == "__main__":
    run()
