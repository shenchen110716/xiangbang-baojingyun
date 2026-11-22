"""P3 合规硬化 smoke（story-p3-compliance AC1–AC7、AC10）。

既有模式：tempfile 临时 SQLite、import backend 之前设 DATABASE_URL、自带
run()；HTTP 面复用 openapi_foundation_smoke 的进程内 ASGI 驱动器与 v2
签名助手，管理端点按惯例直接调 router/service 函数。

覆盖：
- AC1 迁移在隔离 SQLite 上加列 + 回填走通（回填行数 = 明文非空行数），
  回填幂等可重跑（第二次 0 行）；真实 PostgreSQL 验证由编排方做。
- AC2 各写入路径（管理端新增/改证件号/批量、开放平台申报落库）明文 +
  hash + cipher 同事务三处一致（ORM 事件双写层）。
- AC3 读路径：hash 查重/查询、cipher 解密展示；密文空回退明文列（AC10
  回滚通道）；backend 源码静态扫描不再出现明文等值查询。
- AC4/AC5 导出默认脱敏；明文导出仅 admin + 显式参数 + 每次落一条审计。
- AC6 insured:read_sensitive 的明文来自密文列解密（清空明文列仍可读）。
- AC7 轮换：新旧 secret 7 天双活，超期旧 secret 40100，审计落账。
- 红线：全量出口搜明文——请求日志、审计 detail、webhook 事件体、申报
  拒绝原因、以及运行期间收集的全部错误响应文本。
"""
import asyncio
import io
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
if str(ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(ROOT / "tests"))

os.environ.setdefault("ID_ENCRYPTION_KEY", "openapi-compliance-smoke-key")

from openapi_foundation_smoke import http_call, signed_call  # noqa: E402
from openapi_write_smoke import make_id  # noqa: E402

P2_HEAD = "d8e3f6a2b9c4"

RAW_MAIN = make_id("34012319900101123")     # 主测试人员
RAW_SECOND = make_id("34012319900101124")   # 改证件号目标
RAW_BULK = make_id("34012319900101125")     # 批量添加
RAW_DECL = make_id("34012319900101126")     # 开放平台申报
RAW_LEGACY = make_id("34012319900101127")   # 迁移前存量行（仅明文）
RAW_POLICY = make_id("34012319900101128")   # 保单导出人员
ALL_RAW = [RAW_MAIN, RAW_SECOND, RAW_BULK, RAW_DECL, RAW_LEGACY, RAW_POLICY]

# 运行期间收集的错误响应文本（红线断言用）。
_error_texts: list[str] = []


def _collect_error(payload) -> None:
    if payload is not None:
        _error_texts.append(json.dumps(payload, ensure_ascii=False, default=str))


async def _drain_async(resp) -> bytes:
    chunks = []
    async for chunk in resp.body_iterator:
        chunks.append(chunk if isinstance(chunk, bytes) else chunk.encode())
    return b"".join(chunks)


def drain(resp) -> bytes:
    """读出 StreamingResponse 的完整字节内容。"""
    return asyncio.run(_drain_async(resp))


def xlsx_cells(data: bytes) -> list[list]:
    import openpyxl
    book = openpyxl.load_workbook(io.BytesIO(data))
    return [[cell.value for cell in row] for row in book.active.iter_rows()]


def _issue(session, admin, **kwargs):
    from backend.routers.openapi_admin import (
        OpenApiClientIssueIn, issue_openapi_client,
    )
    return issue_openapi_client(OpenApiClientIssueIn(**kwargs),
                                user=admin, session=session)


def _ping(app, key_id: str, secret: str):
    (status, payload, _), _ = signed_call(app, secret, key_id,
                                          "GET", "/openapi/v1/ping")
    return status, payload


# ---------------------------------------------------------------- AC1 迁移

def test_migration_on_isolated_sqlite(folder):
    """AC1 本地部分：stamp P2 head 后 upgrade head，加列 + 索引 + 回填；
    回填行数 = 明文非空行数；重跑迁移外的回填函数仍幂等（见下个用例）。"""
    from backend.core.id_number import id_decrypt, id_hash

    mig_db = Path(folder) / "mig.db"
    with sqlite3.connect(mig_db) as conn:
        conn.execute(
            "CREATE TABLE insured_people ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            "enterprise_id INTEGER, name VARCHAR(80),"
            "id_number VARCHAR(40) DEFAULT '')")
        conn.execute("INSERT INTO insured_people (enterprise_id, name, id_number) "
                     "VALUES (1, '甲', ?)", (RAW_MAIN,))
        conn.execute("INSERT INTO insured_people (enterprise_id, name, id_number) "
                     "VALUES (1, '乙', ?)", (RAW_SECOND,))
        conn.execute("INSERT INTO insured_people (enterprise_id, name, id_number) "
                     "VALUES (1, '丙·无证件号', '')")

    env = {**os.environ, "DATABASE_URL": f"sqlite:///{mig_db}"}
    for args in (["stamp", P2_HEAD], ["upgrade", "head"]):
        proc = subprocess.run([sys.executable, "-m", "alembic", *args],
                              cwd=ROOT, env=env, capture_output=True, text=True)
        assert proc.returncode == 0, f"alembic {args} failed:\n{proc.stdout}\n{proc.stderr}"

    heads = subprocess.run([sys.executable, "-m", "alembic", "heads"],
                           cwd=ROOT, env=env, capture_output=True, text=True)
    head_lines = [l for l in heads.stdout.splitlines() if l.strip()]
    assert len(head_lines) == 1, f"must keep a single alembic head:\n{heads.stdout}"

    with sqlite3.connect(mig_db) as conn:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(insured_people)")}
        assert {"id_number_hash", "id_number_cipher"} <= cols, cols
        indexes = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index'")}
        assert "ix_insured_people_id_number_hash" in indexes, indexes
        rows = list(conn.execute(
            "SELECT id_number, id_number_hash, id_number_cipher "
            "FROM insured_people ORDER BY id"))
    backfilled = [r for r in rows if r[1]]
    plaintext_rows = [r for r in rows if r[0]]
    assert len(backfilled) == len(plaintext_rows) == 2, rows
    for plain, digest, cipher in plaintext_rows:
        assert digest == id_hash(plain), (plain, digest)
        assert id_decrypt(cipher) == plain.upper(), plain
    empty = [r for r in rows if not r[0]][0]
    assert not empty[1] and not empty[2], "无证件号行不得被回填"
    print("  AC1 迁移加列 + 回填（回填行数 = 明文非空行数）ok")


# ------------------------------------------------------- AC2 写路径双写

def _assert_triple(session, person, raw):
    from backend.core.id_number import id_decrypt, id_hash
    session.refresh(person)
    assert person.id_number == raw, "明文列必须保留（AC10 回滚通道）"
    assert person.id_number_hash == id_hash(raw), \
        f"hash 不一致：{person.id_number_hash}"
    assert person.id_number_cipher and id_decrypt(person.id_number_cipher) == raw, \
        "cipher 必须可解密回明文"


def test_dual_write_paths(session, admin, ids):
    """AC2：新增 / 改证件号 / 批量添加 / 开放平台申报落库，三处一致。"""
    from backend.models import InsuredPerson
    from backend.routers.insured import add_person, bulk_add_people, update_person
    from backend.schemas import BulkPersonIn, BulkPersonRow, PersonIn, PersonUpdate
    from sqlalchemy import select

    created = add_person(
        PersonIn(enterprise_id=ids["ent_a"], name="双写甲", phone="13800000001",
                 id_number=RAW_MAIN, enroll=False),
        user=admin, session=session)
    person = session.get(InsuredPerson, created["id"])
    _assert_triple(session, person, RAW_MAIN)
    ids["person_main"] = person.id

    old_hash = person.id_number_hash
    update_person(person.id, PersonUpdate(id_number=RAW_SECOND),
                  user=admin, session=session)
    _assert_triple(session, person, RAW_SECOND)
    assert person.id_number_hash != old_hash, "改证件号后 hash 必须更新"
    update_person(person.id, PersonUpdate(id_number=RAW_MAIN),
                  user=admin, session=session)
    _assert_triple(session, person, RAW_MAIN)

    out = bulk_add_people(
        BulkPersonIn(enterprise_id=ids["ent_a"], position_id=ids["pos_a"],
                     rows=[BulkPersonRow(name="双写乙", id_number=RAW_BULK,
                                         phone="")]),
        user=admin, session=session)
    assert out["ok"], out
    bulk_person = session.get(InsuredPerson, out["ids"][0])
    _assert_triple(session, bulk_person, RAW_BULK)
    ids["person_bulk"] = bulk_person.id
    print("  AC2 管理端写路径（新增/改号/批量）双写 ok")


def test_openapi_declaration_dual_write(app, session, ids):
    """AC2（开放平台落库路径）+ AC3（申报查重走 hash）。"""
    from backend.models import InsuredPerson
    from sqlalchemy import select

    key = ids["key_decl"]
    body = json.dumps({"items": [{"name": "申报丙", "id_number": RAW_DECL,
                                  "phone": "13800000002",
                                  "external_person_id": "EXT-P3-1"}]},
                      ensure_ascii=False).encode()
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", "/openapi/v1/enrollments",
        body=body, extra_headers={"Idempotency-Key": "p3-decl-1"})
    assert status == 200, payload
    session.expire_all()
    person = session.scalar(select(InsuredPerson).where(
        InsuredPerson.name == "申报丙"))
    assert person is not None
    _assert_triple(session, person, RAW_DECL)

    # 重复在保查重必须命中（hash 等值查询）：同证件号再次申报 → 受理即拒。
    body2 = json.dumps({"items": [{"name": "申报丙", "id_number": RAW_DECL,
                                   "phone": ""}]}, ensure_ascii=False).encode()
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "POST", "/openapi/v1/enrollments",
        body=body2, extra_headers={"Idempotency-Key": "p3-decl-2"})
    assert status == 200, payload
    (status, detail, _), _ = signed_call(
        app, key["secret"], key["key_id"], "GET",
        f"/openapi/v1/declarations/{payload['data']['declaration_id']}")
    assert status == 200, detail
    item = detail["data"]["items"][0]
    assert item["status"] == "rejected" and "已在保" in item["reason"], item
    assert RAW_DECL not in json.dumps(detail, ensure_ascii=False), \
        "申报明细不得出现明文证件号"
    print("  AC2/AC3 开放平台申报落库双写 + hash 查重 ok")


# ------------------------------------------- AC1/AC3/AC10 回退读与回填幂等

def test_fallback_read_and_backfill_idempotent(session):
    """密文空 → 回退读明文列（AC10 回滚通道）；回填幂等可重跑。"""
    from backend.core.id_number import id_decrypt, id_hash
    from backend.core.migrations import backfill_insured_id_numbers
    from backend.models import InsuredPerson
    from sqlalchemy import select

    session.connection().exec_driver_sql(
        "INSERT INTO insured_people (enterprise_id, name, phone, id_number, "
        "id_number_hash, id_number_cipher, occupation, occupation_class, "
        "status, insurer_flag_reason, created_at) "
        "VALUES (1, '存量明文', '', ?, '', '', '', '3类', 'draft', '', "
        "CURRENT_TIMESTAMP)", (RAW_LEGACY,))
    session.commit()
    legacy = session.scalar(select(InsuredPerson).where(
        InsuredPerson.name == "存量明文"))
    assert legacy.id_number_cipher in ("", None)
    assert legacy.decrypted_id_number() == RAW_LEGACY, "密文空必须回退明文列"

    first = backfill_insured_id_numbers(session.connection())
    session.commit()
    assert first >= 1, f"回填必须处理存量行，got {first}"
    session.expire_all()
    assert legacy.id_number_hash == id_hash(RAW_LEGACY)
    assert id_decrypt(legacy.id_number_cipher) == RAW_LEGACY
    assert legacy.id_number == RAW_LEGACY, "回填不得清掉明文列（AC10）"

    second = backfill_insured_id_numbers(session.connection())
    session.commit()
    assert second == 0, f"回填必须幂等（重跑 0 行），got {second}"
    print("  AC1/AC10 密文空回退明文 + 回填幂等重跑 ok")


def test_hash_lookup_paths(session):
    """AC3：姓名-证件号一致性查重走 hash 等值。"""
    from backend.routers.insured import _assert_id_number_matches_name
    from fastapi import HTTPException

    _assert_id_number_matches_name(session, RAW_MAIN, "双写甲")  # 同名放行
    try:
        _assert_id_number_matches_name(session, RAW_MAIN, "假名字")
        raise AssertionError("同证件号不同名必须 409")
    except HTTPException as exc:
        assert exc.status_code == 409, exc.status_code
        _collect_error({"detail": exc.detail})
        assert RAW_MAIN not in exc.detail, "错误信息不得出现明文证件号"
    print("  AC3 hash 查重路径 ok")


def test_no_plaintext_equality_queries():
    """AC3 静态扫描：backend 源码不再有明文列等值查询 / LIKE 检索。

    review-p3 m5：正则面放宽，连带抓 in_/startswith/反向比较。
    """
    patterns = [
        (r"InsuredPerson\.id_number\s*==", "InsuredPerson.id_number =="),
        (r"==\s*InsuredPerson\.id_number\b", "== InsuredPerson.id_number（反向比较）"),
        (r"InsuredPerson\.id_number\.like", "InsuredPerson.id_number.like"),
        (r"InsuredPerson\.id_number\.ilike", "InsuredPerson.id_number.ilike"),
        (r"InsuredPerson\.id_number\.in_\(", "InsuredPerson.id_number.in_()"),
        (r"InsuredPerson\.id_number\.startswith", "InsuredPerson.id_number.startswith"),
        (r"InsuredPerson\.id_number\.contains", "InsuredPerson.id_number.contains"),
    ]
    offenders = []
    for path in (ROOT / "backend").rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        for pattern, label in patterns:
            if re.search(pattern, text):
                offenders.append(f"{path}: {label}")
    assert not offenders, offenders
    print("  AC3 静态扫描（无明文等值/子串/集合查询）ok")


def test_backfill_refuses_dev_key_on_non_sqlite():
    """review-p3 B2：非 SQLite 库 + 开发缺省密钥 → 回填入口必须 raise。

    错钥写满 hash/cipher 后，幂等判据只看非空、换对密钥重跑为 0 行，
    投毒不可自愈，所以必须在动库前拦住。用假 dialect 钩子模拟 PG 连接；
    守卫应在执行任何 SQL 之前抛出（假连接没有可用的 execute）。
    """
    from backend.core import config
    from backend.core.migrations import backfill_insured_id_numbers

    class _FakeDialect:
        name = "postgresql"

    class _FakePgConnection:
        dialect = _FakeDialect()

        def execute(self, *args, **kwargs):  # 守卫失效才会走到这里
            raise AssertionError("守卫必须在执行任何 SQL 之前 raise")

    original_key = config.ID_ENCRYPTION_KEY
    try:
        config.ID_ENCRYPTION_KEY = config._DEV_ID_KEY
        try:
            backfill_insured_id_numbers(_FakePgConnection())
            raise AssertionError("开发缺省密钥 + 非 SQLite 库必须拒绝回填")
        except RuntimeError as exc:
            assert "ID_ENCRYPTION_KEY" in str(exc), exc
        # 换成正式密钥后守卫放行（假连接随即在首个 SQL 处断言到达查询阶段）。
        config.ID_ENCRYPTION_KEY = "prod-like-key-for-guard-test-000000000000000"
        try:
            backfill_insured_id_numbers(_FakePgConnection())
            raise AssertionError("放行后应继续执行到 SQL")
        except AssertionError as exc:
            assert "守卫必须在执行任何 SQL 之前 raise" in str(exc), exc
    finally:
        config.ID_ENCRYPTION_KEY = original_key
    print("  B2 回填密钥守卫（非 SQLite + 开发缺省密钥 → raise）ok")


# -------------------------------------------------- AC4/AC5 导出脱敏与审计

def test_export_masking_and_plaintext_audit(session, admin, enterprise_user, ids):
    from backend.core.id_number import mask_id_number
    from backend.models import AuditLog
    from backend.routers.insured import insured_export
    from fastapi import HTTPException
    from sqlalchemy import func, select

    id_param = f"{ids['person_main']},{ids['person_bulk']}"

    # 默认（admin）：脱敏
    data = drain(insured_export(ids=id_param, user=admin, session=session))
    cells = xlsx_cells(data)
    flat = json.dumps(cells, ensure_ascii=False, default=str)
    assert mask_id_number(RAW_MAIN) in flat, cells[:3]
    for raw in ALL_RAW:
        assert raw not in flat, "默认导出不得包含明文证件号"

    # 企业端默认：脱敏；企业端明文参数：403
    data = drain(insured_export(ids=id_param, user=enterprise_user,
                                session=session))
    flat = json.dumps(xlsx_cells(data), ensure_ascii=False, default=str)
    for raw in ALL_RAW:
        assert raw not in flat, "企业端导出不得包含明文证件号"
    try:
        insured_export(ids=id_param, plaintext=True,
                       user=enterprise_user, session=session)
        raise AssertionError("企业端明文导出必须 403")
    except HTTPException as exc:
        assert exc.status_code == 403, exc.status_code
        _collect_error({"detail": exc.detail})

    # admin + plaintext=true：出明文，且每次落一条审计
    before = session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.action == "export_plaintext"))
    data = drain(insured_export(ids=id_param, plaintext=True,
                                user=admin, session=session))
    flat = json.dumps(xlsx_cells(data), ensure_ascii=False, default=str)
    assert RAW_MAIN in flat and RAW_BULK in flat, "admin 明文导出必须出明文"
    after = session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.action == "export_plaintext"))
    assert after == before + 1, "明文导出必须每请求一条审计"
    row = session.scalar(select(AuditLog).where(
        AuditLog.action == "export_plaintext").order_by(AuditLog.id.desc()))
    assert row.user_id == admin.id
    for raw in ALL_RAW:
        assert raw not in (row.detail or "") and raw not in (row.object_id or ""), \
            "审计行本身不得记录明文证件号"
    print("  AC4/AC5 insured 导出默认脱敏 + admin 明文参数 + 审计 ok")


def test_enrollment_and_policy_export_masked(session, admin, enterprise_user, ids):
    from backend.core.id_number import mask_id_number
    from backend.models import AuditLog
    from backend.routers.enrollment import enrollment_export
    from backend.routers.reports import export_policy
    from fastapi import HTTPException
    from sqlalchemy import func, select

    body = drain(enrollment_export(kind="enrollment", date_value="",
                                   plan_id=None, user=admin, session=session))
    text = body.decode("utf-8-sig")
    for raw in ALL_RAW:
        assert raw not in text, "enrollment 导出不得包含明文"
    assert mask_id_number(RAW_BULK) in text, text[:400]

    # 保单导出：默认脱敏；admin 明文参数出明文 + 审计；企业端明文 403。
    data = drain(export_policy(ids["policy_id"], user=admin, session=session))
    flat = json.dumps(xlsx_cells(data), ensure_ascii=False, default=str)
    assert mask_id_number(RAW_POLICY) in flat
    for raw in ALL_RAW:
        assert raw not in flat, "保单导出默认不得包含明文"

    before = session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.action == "export_plaintext"))
    data = drain(export_policy(ids["policy_id"], plaintext=True,
                               user=admin, session=session))
    flat = json.dumps(xlsx_cells(data), ensure_ascii=False, default=str)
    assert RAW_POLICY in flat, "admin 保单明文导出必须出明文"
    after = session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.action == "export_plaintext"))
    assert after == before + 1

    try:
        export_policy(ids["policy_id"], plaintext=True,
                      user=enterprise_user, session=session)
        raise AssertionError("企业端保单明文导出必须 403")
    except HTTPException as exc:
        assert exc.status_code == 403, exc.status_code
        _collect_error({"detail": exc.detail})
    print("  AC4/AC5 enrollment/policy 导出脱敏 + 明文参数审计 ok")


# ------------------------------------------------ AC6 sensitive 走密文解密

def test_sensitive_read_from_cipher(app, session, ids):
    from backend.core.id_number import mask_id_number
    from backend.models import InsuredPerson

    person = session.get(InsuredPerson, ids["person_main"])
    # 清空明文列：若 sensitive 读仍返回完整证件号，证明走的是密文列。
    session.connection().exec_driver_sql(
        "UPDATE insured_people SET id_number='' WHERE id=?", (person.id,))
    session.commit()
    session.expire_all()

    key = ids["key_sens"]
    (status, payload, _), _ = signed_call(
        app, key["secret"], key["key_id"], "GET",
        f"/openapi/v1/insured-persons/{person.id}")
    assert status == 200, payload
    assert payload["data"]["id_number"] == RAW_MAIN, \
        f"sensitive 读必须从密文解密：{payload['data']['id_number']}"

    key_read = ids["key_read"]
    (status, payload, _), _ = signed_call(
        app, key_read["secret"], key_read["key_id"], "GET",
        f"/openapi/v1/insured-persons/{person.id}")
    assert status == 200, payload
    masked = payload["data"]["id_number"]
    assert masked == f"{RAW_MAIN[:3]}{'*' * 11}{RAW_MAIN[-4:]}", masked

    # 恢复明文列（双写回滚通道继续成立）。
    session.connection().exec_driver_sql(
        "UPDATE insured_people SET id_number=? WHERE id=?",
        (RAW_MAIN, person.id))
    session.commit()
    session.expire_all()
    # 管理端展示读也走解密 getter
    assert person.decrypted_id_number() == RAW_MAIN
    print("  AC6 insured:read_sensitive 明文来自密文列 ok")


# ---------------------------------------------------------- AC7 密钥轮换

def test_rotation_grace_and_expiry(app, session, admin, ids):
    from backend.models import AuditLog, OpenApiClient
    from backend.routers.openapi_admin import (
        list_openapi_clients, rotate_openapi_client,
    )
    from sqlalchemy import select

    issued = _issue(session, admin, enterprise_id=ids["ent_a"],
                    name="轮换用", scopes=["probe:read"], env="prod")
    key_id, old_secret = issued["key_id"], issued["secret"]
    status, _ = _ping(app, key_id, old_secret)
    assert status == 200

    rotated = rotate_openapi_client(issued["id"], user=admin, session=session)
    new_secret = rotated["secret"]
    assert new_secret and new_secret != old_secret
    assert rotated["key_id"] == key_id and rotated["rotated_at"], rotated

    # 审计落账且不含 secret
    row = session.scalar(select(AuditLog).where(
        AuditLog.action == "openapi_client_rotate").order_by(AuditLog.id.desc()))
    assert row is not None and new_secret not in (row.detail or ""), row

    # 列表/详情永不回 secret
    rows = list_openapi_clients(enterprise_id=ids["ent_a"], user=admin,
                                session=session)
    flat = json.dumps(rows, ensure_ascii=False, default=str)
    assert new_secret not in flat and old_secret not in flat

    # 宽限期内：新旧都可验签
    status, _ = _ping(app, key_id, new_secret)
    assert status == 200, "新 secret 必须立即生效"
    status, _ = _ping(app, key_id, old_secret)
    assert status == 200, "旧 secret 宽限期内必须可用"

    # 超过 7 天：旧 secret 废止（40100），新 secret 不受影响
    client = session.get(OpenApiClient, issued["id"])
    client.rotated_at = datetime.now(timezone.utc) - timedelta(days=7, hours=1)
    session.commit()
    status, payload = _ping(app, key_id, old_secret)
    assert status == 401 and payload["code"] == 40100, payload
    _collect_error(payload)
    status, _ = _ping(app, key_id, new_secret)
    assert status == 200

    # 再次轮换：上上任 secret 立即失效（secondary 槽被顶掉）
    rotated2 = rotate_openapi_client(issued["id"], user=admin, session=session)
    status, _ = _ping(app, key_id, rotated2["secret"])
    assert status == 200
    status, _ = _ping(app, key_id, new_secret)
    assert status == 200, "刚被轮换下的 secret 在宽限期内可用"
    status, payload = _ping(app, key_id, old_secret)
    assert status == 401, "更早的 secret 必须已废止"
    _collect_error(payload)
    print("  AC7 轮换 7 天双活 + 超期废止 + 审计 ok")


# ------------------------------------------------------- 红线：出口搜明文

def test_no_plaintext_in_any_exit(session):
    """全文搜索所有出口存储：请求日志、审计、webhook 事件体、申报拒绝
    原因、导出任务参数/错误，以及运行期间收集的错误响应文本。"""
    from backend.models import (
        AuditLog, ExportTask, OpenApiDeclarationItem, OpenApiRequestLog,
        OpenApiWebhookDelivery,
    )
    from sqlalchemy import select

    corpus: list[str] = []
    for log in session.scalars(select(OpenApiRequestLog)):
        corpus.append(f"{log.method} {log.path} {log.ip} {log.key_id}")
    for row in session.scalars(select(AuditLog)):
        corpus.append(f"{row.action} {row.object_type} {row.object_id} {row.detail}")
    for d in session.scalars(select(OpenApiWebhookDelivery)):
        corpus.append(f"{d.event_type} {d.payload} {d.last_error}")
    for item in session.scalars(select(OpenApiDeclarationItem)):
        corpus.append(item.reason or "")
    for task in session.scalars(select(ExportTask)):
        corpus.append(f"{task.params_json} {task.error}")
    corpus.extend(_error_texts)

    text = "\n".join(corpus)
    for raw in ALL_RAW:
        assert raw not in text, f"出口存储泄漏明文证件号：{raw}"
    print("  红线：日志/审计/事件/错误全文无明文证件号 ok")


# ------------------------------------------------------------------------ run

def run():
    with tempfile.TemporaryDirectory(prefix="xbb-openapi-compliance-") as folder:
        os.environ["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        os.environ["ADMIN_PASSWORD"] = "admin123"
        os.environ["ENTERPRISE_PASSWORD"] = "enterprise123"
        os.environ["OPENAPI_RATE_LIMIT_PER_SEC"] = "100000"

        from sqlalchemy import select

        from backend.app import app, startup
        from backend.core.db import SessionLocal
        from backend.models import (
            Enterprise, InsurancePlan, InsuredPerson, Policy, PolicyMember,
            User, WorkPosition,
        )

        startup()

        with SessionLocal() as session:
            admin = session.scalar(select(User).where(User.username == "admin"))
            enterprise_user = session.scalar(
                select(User).where(User.username == "enterprise"))
            ent_a = session.scalar(select(Enterprise))
            ent_a.usage_balance = 10000.0
            pos_a = WorkPosition(enterprise_id=ent_a.id, name="理货员",
                                 occupation_class="3类", status="approved")
            session.add(pos_a)
            session.flush()
            ids = {"ent_a": ent_a.id, "pos_a": pos_a.id}

            # 保单导出用fixture：plan + policy + member + 在保人员
            plan = InsurancePlan(name="合规测试产品", insurer="合规保司",
                                 price=100.0, commission_rate=0.1)
            session.add(plan)
            session.flush()
            policy = Policy(policy_no="P3-COMPLIANCE-1", enterprise_id=ent_a.id,
                            plan_id=plan.id, status="active")
            p_policy = InsuredPerson(enterprise_id=ent_a.id, name="保单人员",
                                     phone="", id_number=RAW_POLICY,
                                     status="active")
            session.add_all([policy, p_policy])
            session.flush()
            session.add(PolicyMember(policy_id=policy.id, person_id=p_policy.id,
                                     status="active"))
            session.commit()
            ids["policy_id"] = policy.id

            ids["key_read"] = _issue(session, admin, enterprise_id=ent_a.id,
                                     name="只读", scopes=["insured:read"],
                                     env="prod")
            ids["key_sens"] = _issue(
                session, admin, enterprise_id=ent_a.id, name="敏感读",
                scopes=["insured:read", "insured:read_sensitive"], env="prod")
            ids["key_decl"] = _issue(session, admin, enterprise_id=ent_a.id,
                                     name="申报", scopes=["declaration:write"],
                                     env="prod")

            test_dual_write_paths(session, admin, ids)
            test_openapi_declaration_dual_write(app, session, ids)
            test_fallback_read_and_backfill_idempotent(session)
            test_hash_lookup_paths(session)
            test_no_plaintext_equality_queries()
            test_backfill_refuses_dev_key_on_non_sqlite()
            test_export_masking_and_plaintext_audit(
                session, admin, enterprise_user, ids)
            test_enrollment_and_policy_export_masked(
                session, admin, enterprise_user, ids)
            test_sensitive_read_from_cipher(app, session, ids)
            test_rotation_grace_and_expiry(app, session, admin, ids)
            test_no_plaintext_in_any_exit(session)

        test_migration_on_isolated_sqlite(folder)

    print("openapi compliance smoke passed")


if __name__ == "__main__":
    run()
