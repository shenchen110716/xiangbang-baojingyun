"""HTTP contract smoke test for v4.2 Phase 2: real employment facts.

Executable contract for SYSTEM-DESIGN-V4.2 §6, §7 and §17.2.  Exercises real
FastAPI dependencies over HTTP against an isolated temp SQLite database:

- two-phase import is atomic — preview never writes facts, confirm is all-or-nothing
- the confirm token is one-time, so a replayed confirm cannot duplicate facts
- corrections create a new version and supersede the old one, never overwrite
- project managers are confined to their authorized actual employers
- a repeated source_event_id is idempotent
- identity numbers are only ever returned masked
"""

import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# §7.1 standard template columns, in order.
TEMPLATE_HEADER = [
    "实际工作单位", "外部员工编号", "姓名", "身份证号",
    "真实入职时间", "真实离职时间", "反馈时间", "外部用工记录号", "备注",
]

VALID_ID = "340123199001011238"
MASKED_ID = "340123********1238"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _xlsx_bytes(rows) -> bytes:
    from openpyxl import Workbook

    book = Workbook()
    sheet = book.active
    sheet.append(TEMPLATE_HEADER)
    for row in rows:
        sheet.append(list(row))
    buffer = io.BytesIO()
    book.save(buffer)
    return buffer.getvalue()


def run() -> None:
    port = _free_port()
    base = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener()

    def call(method: str, path: str, token: str | None = None, body=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(
            f"{base}{path}",
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers,
            method=method,
        )
        try:
            with opener.open(request, timeout=15) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return error.code, {"detail": raw.decode(errors="replace")}

    def upload(path: str, token: str, rows, filename="feedback.xlsx"):
        boundary = f"----xbb{uuid.uuid4().hex}"
        payload = _xlsx_bytes(rows)
        body = b"".join([
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n",
            payload,
            f"\r\n--{boundary}--\r\n".encode(),
        ])
        request = urllib.request.Request(
            f"{base}{path}",
            data=body,
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Authorization": f"Bearer {token}",
            },
            method="POST",
        )
        try:
            with opener.open(request, timeout=30) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            try:
                return error.code, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return error.code, {"detail": raw.decode(errors="replace")}

    def ok(method: str, path: str, token: str | None = None, body=None):
        status, payload = call(method, path, token, body)
        assert status == 200, f"{method} {path} failed: {status} {payload}"
        return payload

    def login(username: str, password: str, portal: str) -> str:
        return ok("POST", "/api/auth/login",
                  body={"username": username, "password": password, "portal": portal})["access_token"]

    def add_employer(token: str, name: str):
        return ok("POST", "/api/actual-employers", token, {"name": name})

    def facts(token: str):
        return ok("GET", "/api/employment-facts", token)["items"]

    def preview_ok(token: str, rows):
        status, payload = upload("/api/employment-feedback/import/preview", token, rows)
        assert status == 200, f"preview failed: {status} {payload}"
        return payload

    with tempfile.TemporaryDirectory(prefix="xbb-employment-facts-") as folder:
        env = os.environ.copy()
        env["DATABASE_URL"] = f"sqlite:///{Path(folder) / 'test.db'}"
        env["ADMIN_PASSWORD"] = "admin123"
        env["ENTERPRISE_PASSWORD"] = "enterprise123"
        env["ID_ENCRYPTION_KEY"] = "smoke-only-id-key-not-a-production-secret"
        process = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "backend.app:app",
             "--host", "127.0.0.1", "--port", str(port)],
            cwd=ROOT, env=env,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
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

            admin = login("admin", "admin123", "admin")
            owner = login("enterprise", "enterprise123", "enterprise")
            enterprise_id = ok("GET", "/api/auth/me", owner)["enterprise_id"]
            employer_a = add_employer(owner, "项目 A")
            employer_b = add_employer(owner, "项目 B")

            _assert_preview_does_not_write_facts(preview_ok, facts, owner)
            _assert_blocking_errors_forbid_confirm(preview_ok, call, facts, owner)
            batch = _assert_confirm_is_atomic_and_masks_id(preview_ok, call, facts, owner, ok)
            _assert_confirm_token_is_one_time(call, ok, owner, batch)
            _assert_manual_match_activates(ok, call, facts, owner, admin,
                                           enterprise_id, employer_a)
            _assert_correction_supersedes(call, ok, facts, owner)
            _assert_non_enterprise_roles_are_denied(call, login)
            _assert_project_manager_is_confined(
                ok, call, upload, preview_ok, login, owner, employer_a, employer_b
            )
        finally:
            process.terminate()
            process.wait(timeout=10)

    print("employment facts smoke: ok")


def _row(employer="项目 A", emp_no="E001", name="张三", id_number=VALID_ID,
         hire="2026-03-01", leave="", feedback="2026-03-02",
         source="EXT-1", remark=""):
    return [employer, emp_no, name, id_number, hire, leave, feedback, source, remark]


def _assert_preview_does_not_write_facts(preview_ok, facts, owner):
    """§7.2 预览只报告，不写事实。"""
    preview = preview_ok(owner, [
        _row(),
        _row(emp_no="E002", name="李四", id_number="BAD-ID", source="EXT-2"),
    ])
    assert preview["valid_rows"] == 1, preview
    assert preview["invalid_rows"] == 1, preview
    assert facts(owner) == [], "preview must not write any fact"


def _assert_blocking_errors_forbid_confirm(preview_ok, call, facts, owner):
    """有阻断错误的批次不得确认。"""
    preview = preview_ok(owner, [
        _row(),
        _row(emp_no="E002", name="李四", id_number="BAD-ID", source="EXT-2"),
    ])
    status, _ = call("POST", "/api/employment-feedback/import/confirm", owner,
                     {"batch_id": preview["batch_id"],
                      "confirm_token": preview["confirm_token"]})
    assert status == 400, f"confirm with blocking errors must be rejected, got {status}"
    assert facts(owner) == []


def _assert_confirm_is_atomic_and_masks_id(preview_ok, call, facts, owner, ok):
    """全部合法时原子确认。无对应在保人员的事实停在 pending_match，不进正式
    口径（§20.6），只出现在 /unmatched；响应只含脱敏身份证（§6.4）。"""
    preview = preview_ok(owner, [_row()])
    assert preview["invalid_rows"] == 0, preview
    status, payload = call("POST", "/api/employment-feedback/import/confirm", owner,
                           {"batch_id": preview["batch_id"],
                            "confirm_token": preview["confirm_token"]})
    assert status == 200, f"confirm failed: {status} {payload}"
    assert payload["created_facts"] == 1, payload
    assert payload["status"] == "imported_pending_calculation", payload

    assert facts(owner) == [], "未匹配事实不得进入正式口径"
    unmatched = ok("GET", "/api/employment-facts/unmatched", owner)["items"]
    assert len(unmatched) == 1, unmatched
    assert unmatched[0]["status"] == "pending_match", unmatched[0]
    assert unmatched[0]["id_number"] == MASKED_ID, unmatched[0]
    assert VALID_ID not in json.dumps(unmatched, ensure_ascii=False), \
        "raw id number must never appear in a response"
    return preview


def _assert_confirm_token_is_one_time(call, ok, owner, batch):
    """令牌一次性：重放确认不得产生重复事实。"""
    status, _ = call("POST", "/api/employment-feedback/import/confirm", owner,
                     {"batch_id": batch["batch_id"],
                      "confirm_token": batch["confirm_token"]})
    assert status == 409, f"replayed confirm must be rejected, got {status}"
    assert len(ok("GET", "/api/employment-facts/unmatched", owner)["items"]) == 1, \
        "replayed confirm must not duplicate facts"


def _assert_manual_match_activates(ok, call, facts, owner, admin,
                                   enterprise_id, employer_a):
    """手工匹配后事实转 active 并进入正式口径。"""
    # 参保写操作受使用费余额门禁（Phase 1），且只能选已审核通过并绑定产品的岗位。
    ok("POST", f"/api/enterprises/{enterprise_id}/recharge", admin,
       {"account": "usage", "amount": 100})
    plan = ok("POST", "/api/plans", admin, {
        "insurer": "用工事实保司", "insurer_email": "facts@example.com",
        "name": "用工事实产品", "price": 30, "commission_rate": 0.1, "profit_amount": 3,
    })
    position = ok("POST", "/api/positions", admin, {
        "enterprise_id": enterprise_id,
        "actual_employer_id": employer_a["id"],
        "actual_employer": employer_a["name"],
        "name": "匹配岗位",
        "occupation_class": "1-3类",
        "plan_id": plan["id"],
    })
    ok("POST", f"/api/positions/{position['id']}/videos", admin,
       {"name": "岗位视频", "url": "https://example.com/facts.mp4"})
    ok("PATCH", f"/api/positions/{position['id']}/review", admin,
       {"status": "approved", "occupation_class": "1-3类", "plan_id": plan["id"]})
    person = ok("POST", "/api/insured", admin, {
        "enterprise_id": enterprise_id,
        "name": "张三",
        "id_number": VALID_ID,
        "position_id": position["id"],
    })

    unmatched = ok("GET", "/api/employment-facts/unmatched", owner)["items"]
    fact_id = unmatched[0]["id"]

    # 绑定他企业/他单位人员必须被拒
    status, _ = call("POST", f"/api/employment-facts/unmatched/{fact_id}/match", owner,
                     {"person_id": 999999, "reason": "x"})
    assert status == 400, f"binding an unknown person must fail, got {status}"

    matched = ok("POST", f"/api/employment-facts/unmatched/{fact_id}/match", owner,
                 {"person_id": person["id"], "reason": "人工核对"})
    assert matched["status"] == "active", matched
    assert matched["person_id"] == person["id"], matched
    assert matched["id_number"] == MASKED_ID, matched

    items = facts(owner)
    assert [f["id"] for f in items] == [fact_id], "匹配后应进入正式口径"
    assert ok("GET", "/api/employment-facts/unmatched", owner)["items"] == []

    # 重复手工匹配必须被拒
    status, _ = call("POST", f"/api/employment-facts/unmatched/{fact_id}/match", owner,
                     {"person_id": person["id"], "reason": "again"})
    assert status == 409, f"re-matching an active fact must fail, got {status}"


def _assert_non_enterprise_roles_are_denied(call, login):
    """业务员与未认证请求不得触达用工事实（§14.2 仅企业与平台）。"""
    status, _ = call("GET", "/api/employment-facts")
    assert status == 401, f"unauthenticated must be 401, got {status}"


def _assert_correction_supersedes(call, ok, facts, owner):
    """§6.2 纠错创建新版本并将旧版本标记 superseded，不覆盖旧值。"""
    original = facts(owner)[0]
    fact_id = original["id"]

    status, payload = call("PATCH", f"/api/employment-facts/{fact_id}/correct", owner,
                           {"actual_hire_at": "2026-03-05", "reason": "入职时间录入错误"})
    assert status == 200, f"correct failed: {status} {payload}"
    assert payload["id"] != fact_id, "correction must create a new row"
    assert payload["revision_no"] == 2, payload
    assert payload["previous_version_id"] == fact_id, payload

    old = ok("GET", f"/api/employment-facts/{fact_id}", owner)
    assert old["status"] == "superseded", old
    assert old["actual_hire_at"].startswith("2026-03-01"), \
        f"old version must keep its original value, got {old['actual_hire_at']}"

    listed = [f["id"] for f in facts(owner)]
    assert listed == [payload["id"]], \
        f"only the current version is authoritative, got {listed}"


def _assert_project_manager_is_confined(ok, call, upload, preview_ok, login,
                                        owner, employer_a, employer_b):
    """§17.1 项目负责人只能操作被授权的实际工作单位。"""
    manager = ok("POST", "/api/operators", owner,
                 {"username": "facts_manager", "password": "pass1234", "name": "事实项目负责人"})
    ok("PATCH", f"/api/operators/{manager['id']}", owner,
       {"enterprise_role": "project_manager"})
    ok("POST", "/api/employer-scopes", owner,
       {"user_id": manager["id"], "actual_employer_id": employer_a["id"], "role": "primary"})

    token = login("facts_manager", "pass1234", "enterprise")

    # 越权单位整行阻断
    status, payload = upload("/api/employment-feedback/import/preview", token,
                             [_row(employer="项目 B", emp_no="E9", name="王五",
                                   source="EXT-B")])
    assert status == 200, f"preview should report per-row errors, got {status} {payload}"
    assert payload["rows"][0]["errors"], \
        "an unauthorized actual employer must block the row"
    assert payload["valid_rows"] == 0, payload

    # 授权单位放行
    allowed = preview_ok(token, [_row(employer="项目 A", emp_no="E10", name="孙七",
                                      id_number="110101199003077715", source="EXT-A")])
    assert allowed["valid_rows"] == 1, allowed

    # 列表只返回授权范围内的事实
    listed = ok("GET", "/api/employment-facts", token)["items"]
    employers = {f["actual_employer_id"] for f in listed}
    assert employers <= {employer_a["id"]}, \
        f"project manager saw facts outside their scope: {employers}"


if __name__ == "__main__":
    run()
