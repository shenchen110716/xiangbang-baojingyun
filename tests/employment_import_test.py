"""Two-phase atomic import contract (v4.2 §7.2, §6.1).

The import is the only writer of authoritative facts, so its guarantees are
load-bearing: preview never writes, confirm is all-or-nothing, and the token is
bound to one uploader + one file + one preview version and burns on use.
"""
import io
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "import-test-key")

from fastapi import HTTPException
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    ActualEmployer,
    EmploymentFact,
    EmploymentFeedbackBatch,
    Enterprise,
    User,
    UserEmployerScope,
)
from backend.services import employment_import
from backend.services.employment_import import confirm_import, preview_import

HEADER = ["实际工作单位", "外部员工编号", "姓名", "身份证号",
          "真实入职时间", "真实离职时间", "反馈时间", "外部用工记录号", "备注"]
GOOD = ["项目 A", "E001", "张三", "340123199001011238", "2026-03-01", "", "2026-03-02", "EXT-1", ""]
GOOD2 = ["项目 A", "E002", "李四", "110101199003077715", "2026-03-01", "", "2026-03-02", "EXT-2", ""]
BAD_ID = ["项目 A", "E003", "王五", "BAD-ID", "2026-03-01", "", "2026-03-02", "EXT-3", ""]
FOR_B = ["项目 B", "E004", "赵六", "50010319900307611X", "2026-03-01", "", "2026-03-02", "EXT-4", ""]


def _book(rows) -> bytes:
    from openpyxl import Workbook
    book = Workbook()
    sheet = book.active
    sheet.append(HEADER)
    for row in rows:
        sheet.append(list(row))
    buf = io.BytesIO()
    book.save(buf)
    return buf.getvalue()


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="导入企业")
    ctx.other_enterprise = Enterprise(name="他企业")
    session.add_all([ctx.enterprise, ctx.other_enterprise])
    session.flush()
    ctx.employer_a = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    ctx.employer_b = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 B")
    session.add_all([ctx.employer_a, ctx.employer_b])
    session.flush()
    ctx.owner = User(username="imp_owner", password_hash="x", name="主管", role="enterprise",
                     enterprise_id=ctx.enterprise.id, enterprise_role="owner", is_owner=True)
    ctx.other_owner = User(username="imp_owner2", password_hash="x", name="另一主管",
                           role="enterprise", enterprise_id=ctx.enterprise.id,
                           enterprise_role="owner", is_owner=True)
    ctx.manager = User(username="imp_mgr", password_hash="x", name="负责人", role="enterprise",
                       enterprise_id=ctx.enterprise.id, enterprise_role="project_manager",
                       is_owner=False)
    session.add_all([ctx.owner, ctx.other_owner, ctx.manager])
    session.flush()
    session.add(UserEmployerScope(
        user_id=ctx.manager.id, actual_employer_id=ctx.employer_a.id,
        enterprise_id=ctx.enterprise.id, responsibility_type="primary",
        granted_by=ctx.owner.id, status="active", assigned_at=datetime.now(timezone.utc)))
    session.flush()
    return ctx


def _seed_person(session, ctx, *, id_number, employer=None, name="张三"):
    from backend.models import InsuredPerson, WorkPosition
    employer = employer or ctx.employer_a
    position = WorkPosition(enterprise_id=ctx.enterprise.id,
                            actual_employer_id=employer.id, name="岗位",
                            occupation_class="1-3类")
    session.add(position)
    session.flush()
    person = InsuredPerson(enterprise_id=ctx.enterprise.id, name=name,
                           id_number=id_number, position_id=position.id)
    session.add(person)
    session.flush()
    return person


def _test_unmatched_fact_stays_out_of_metrics(session, ctx):
    """无对应在保人员时事实仍记录，但停在 pending_match，不进入正式口径。"""
    _clear(session)
    out = _preview(session, ctx, [GOOD])
    assert out["rows"][0]["warnings"], "未匹配应给出警告而非阻断"
    assert not out["rows"][0]["errors"], "未匹配不是阻断错误"
    confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                   confirm_token=out["confirm_token"])
    session.commit()
    facts = _facts(session)
    assert len(facts) == 1
    assert facts[0].status == "pending_match", facts[0].status
    assert facts[0].person_id is None
    print("  unmatched stays pending_match ok")


def _preview(session, ctx, rows, user=None, filename="a.xlsx"):
    return preview_import(session, user or ctx.owner, enterprise_id=ctx.enterprise.id,
                          filename=filename, content=_book(rows))


def _facts(session):
    return list(session.scalars(select(EmploymentFact)))


def _clear(session):
    from backend.models import InsuredPerson, WorkPosition
    session.query(EmploymentFact).delete()
    session.query(EmploymentFeedbackBatch).delete()
    session.query(InsuredPerson).delete()
    session.query(WorkPosition).delete()
    session.flush()
    session.commit()


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    with Session(engine) as session:
        ctx = _setup(session)
        session.commit()

        _test_preview_writes_no_facts(session, ctx)
        _test_unmatched_fact_stays_out_of_metrics(session, ctx)
        _test_confirm_blocked_while_blocking_error_remains(session, ctx)
        _test_confirm_creates_facts(session, ctx)
        _test_confirm_token_is_single_use(session, ctx)
        _test_token_is_bound_to_uploader(session, ctx)
        _test_stale_preview_version_is_rejected(session, ctx)
        _test_confirm_is_atomic_on_mid_write_failure(session, ctx)
        _test_same_file_hash_cannot_be_confirmed_twice(session, ctx)
        _test_manager_rows_outside_scope_are_blocking(session, ctx)
    print("employment import tests passed")


def _test_preview_writes_no_facts(session, ctx):
    _clear(session)
    out = _preview(session, ctx, [GOOD])
    assert out["valid_rows"] == 1, out
    assert out["invalid_rows"] == 0, out
    assert out["confirm_token"], "preview must issue a confirm token"
    assert _facts(session) == [], "预览不写事实"
    assert out["rows"][0]["masked_id"] == "340123********1238"
    assert "340123199001011238" not in repr(out), "预览响应不得含身份证原文"
    print("  preview writes no facts ok")


def _test_confirm_blocked_while_blocking_error_remains(session, ctx):
    _clear(session)
    out = _preview(session, ctx, [GOOD, BAD_ID])
    assert out["valid_rows"] == 1 and out["invalid_rows"] == 1, out
    try:
        confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                       confirm_token=out["confirm_token"])
    except HTTPException as exc:
        assert exc.status_code == 400, exc.status_code
    else:
        raise AssertionError("confirm must be blocked while a blocking error remains")
    session.rollback()
    assert _facts(session) == [], "禁止部分确认"
    print("  blocking error forbids confirm ok")


def _test_confirm_creates_facts(session, ctx):
    """匹配到在保人员的事实进入 active；匹配不到的停在 pending_match（§20.6）。"""
    _clear(session)
    _seed_person(session, ctx, id_number=GOOD[3])
    out = _preview(session, ctx, [GOOD])
    result = confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                            confirm_token=out["confirm_token"])
    session.commit()
    assert result["created_facts"] == 1, result
    facts = _facts(session)
    assert len(facts) == 1
    assert facts[0].status == "active", f"matched fact should be active, got {facts[0].status}"
    assert facts[0].person_id is not None, "matched fact must bind the person"
    assert facts[0].id_number_cipher and facts[0].id_number_hash
    batch = session.get(EmploymentFeedbackBatch, out["batch_id"])
    assert batch.status == "imported_pending_calculation", batch.status
    assert batch.confirm_token_digest is None, "令牌必须一次性作废"
    print("  confirm creates facts ok")


def _test_confirm_token_is_single_use(session, ctx):
    _clear(session)
    out = _preview(session, ctx, [GOOD])
    confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                   confirm_token=out["confirm_token"])
    session.commit()
    try:
        confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                       confirm_token=out["confirm_token"])
    except HTTPException as exc:
        assert exc.status_code == 409, exc.status_code
    else:
        raise AssertionError("a replayed token must be rejected")
    session.rollback()
    assert len(_facts(session)) == 1, "重放不得产生重复事实"
    print("  token single use ok")


def _test_token_is_bound_to_uploader(session, ctx):
    _clear(session)
    out = _preview(session, ctx, [GOOD])
    try:
        confirm_import(session, ctx.other_owner, batch_id=out["batch_id"],
                       confirm_token=out["confirm_token"])
    except HTTPException as exc:
        assert exc.status_code == 403, exc.status_code
    else:
        raise AssertionError("only the uploader may confirm")
    session.rollback()
    print("  token bound to uploader ok")


def _test_stale_preview_version_is_rejected(session, ctx):
    _clear(session)
    first = _preview(session, ctx, [GOOD])
    session.commit()
    # 对同一文件重新预览 → 同一批次 version+1，旧令牌随之失效：确认一份用户
    # 没看过的报告，会让两阶段流程失去意义。
    again = _preview(session, ctx, [GOOD])
    session.commit()
    assert again["batch_id"] == first["batch_id"], "同一文件应复用同一批次"
    assert again["preview_version"] == first["preview_version"] + 1
    try:
        confirm_import(session, ctx.owner, batch_id=first["batch_id"],
                       confirm_token=first["confirm_token"])
    except HTTPException as exc:
        assert exc.status_code == 409, exc.status_code
    else:
        raise AssertionError("a stale preview token must be rejected")
    session.rollback()
    print("  stale preview rejected ok")


def _test_confirm_is_atomic_on_mid_write_failure(session, ctx):
    """写到一半失败必须整体回滚，且令牌未被消耗。"""
    _clear(session)
    out = _preview(session, ctx, [GOOD, GOOD2])
    session.commit()

    original = employment_import._write_fact
    calls = {"n": 0}

    def _boom(*args, **kwargs):
        calls["n"] += 1
        if calls["n"] == 2:
            raise RuntimeError("simulated mid-write failure")
        return original(*args, **kwargs)

    employment_import._write_fact = _boom
    try:
        confirm_import(session, ctx.owner, batch_id=out["batch_id"],
                       confirm_token=out["confirm_token"])
    except RuntimeError:
        session.rollback()
    else:
        raise AssertionError("the injected failure should propagate")
    finally:
        employment_import._write_fact = original

    assert _facts(session) == [], "中途失败必须全部回滚"
    batch = session.get(EmploymentFeedbackBatch, out["batch_id"])
    assert batch.status == "previewed", f"回滚后批次应回到 previewed，实为 {batch.status}"
    assert batch.confirm_token_digest is not None, "回滚后令牌应仍可用"
    print("  atomic rollback ok")


def _test_same_file_hash_cannot_be_confirmed_twice(session, ctx):
    """§6.1 相同企业、来源和文件哈希不得重复确认。"""
    _clear(session)
    first = _preview(session, ctx, [GOOD])
    confirm_import(session, ctx.owner, batch_id=first["batch_id"],
                   confirm_token=first["confirm_token"])
    session.commit()

    second = _preview(session, ctx, [GOOD])   # 同样内容 → 同样 file hash
    try:
        confirm_import(session, ctx.owner, batch_id=second["batch_id"],
                       confirm_token=second["confirm_token"])
        session.commit()
    except HTTPException as exc:
        assert exc.status_code == 409, exc.status_code
    except Exception:
        session.rollback()
    else:
        raise AssertionError("the same file must not be confirmed twice")
    session.rollback()
    assert len(_facts(session)) == 1, "同一文件不得产生第二批事实"
    print("  duplicate file blocked ok")


def _test_manager_rows_outside_scope_are_blocking(session, ctx):
    _clear(session)
    out = _preview(session, ctx, [FOR_B], user=ctx.manager)
    assert out["rows"][0]["errors"], "越权单位必须整行阻断"
    assert out["valid_rows"] == 0, out
    # 授权单位放行
    allowed = _preview(session, ctx, [GOOD], user=ctx.manager)
    assert allowed["valid_rows"] == 1, allowed
    print("  manager scope blocking ok")


if __name__ == "__main__":
    run()
