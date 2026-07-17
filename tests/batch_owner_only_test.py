"""Employment-fact batch surfaces are owner-only (v4.2 §13.1, §13.2).

§13.1 lists 上传预览、确认和批次详情 as enterprise-owner capabilities; §13.2
states 项目负责人不提供全企业用工事实批次确认或授权关系维护能力.

A batch row is enterprise-wide by nature — it names the uploaded file, its row
counts and who imported it — so letting any `enterprise` user read the list
leaks other projects' work to a manager authorized for none of them. The bug
was gating on the coarse login role (`enterprise`), which a project manager
also has, instead of on ownership.
"""
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

os.environ.setdefault("ID_ENCRYPTION_KEY", "batch-owner-test")

from fastapi import HTTPException
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.core.db import Base
from backend.models import (
    ActualEmployer,
    EmploymentFeedbackBatch,
    Enterprise,
    User,
    UserEmployerScope,
)
from backend.routers import employment_facts as facts_router
from backend.routers.employment_facts import get_batch, list_batches, require_owner_or_admin


def _now():
    return datetime.now(timezone.utc)


class _Ctx:
    pass


def _setup(session) -> _Ctx:
    ctx = _Ctx()
    ctx.enterprise = Enterprise(name="批次权限企业")
    session.add(ctx.enterprise)
    session.flush()
    ctx.employer_a = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 A")
    ctx.employer_b = ActualEmployer(enterprise_id=ctx.enterprise.id, name="项目 B")
    session.add_all([ctx.employer_a, ctx.employer_b])
    session.flush()

    ctx.owner = User(username="b_owner", password_hash="x", name="主管",
                     role="enterprise", enterprise_id=ctx.enterprise.id,
                     enterprise_role="owner", is_owner=True)
    ctx.manager = User(username="b_manager", password_hash="x", name="负责人",
                       role="enterprise", enterprise_id=ctx.enterprise.id,
                       enterprise_role="project_manager", is_owner=False)
    ctx.admin = User(username="b_admin", password_hash="x", name="平台",
                     role="admin", enterprise_role=None)
    session.add_all([ctx.owner, ctx.manager, ctx.admin])
    session.flush()

    # 即便获授权一个单位，也不得看到全企业批次。
    session.add(UserEmployerScope(
        user_id=ctx.manager.id, enterprise_id=ctx.enterprise.id,
        actual_employer_id=ctx.employer_a.id, responsibility_type="primary",
        granted_by=ctx.owner.id, status="active", assigned_at=_now()))

    ctx.batch = EmploymentFeedbackBatch(
        enterprise_id=ctx.enterprise.id, actual_employer_id=ctx.employer_b.id,
        source_type="manual_import", source_filename="机密名单.xlsx",
        status="completed", created_at=_now())
    session.add(ctx.batch)
    session.flush()
    return ctx


def run() -> None:
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)

    with Session(engine) as session:
        ctx = _setup(session)
        _test_project_manager_cannot_list_batches(session, ctx)
        _test_project_manager_cannot_read_a_batch(session, ctx)
        _test_owner_still_sees_batches(session, ctx)
        _test_admin_still_sees_batches(session, ctx)

    print("batch owner-only tests passed")


def _test_project_manager_cannot_list_batches(session, ctx):
    """门禁本身：项目负责人被拒，主管与平台放行。"""
    try:
        require_owner_or_admin(ctx.manager)
    except HTTPException as exc:
        assert exc.status_code == 403, exc.status_code
    else:
        raise AssertionError("§13.2 项目负责人不得读取全企业导入批次")
    assert require_owner_or_admin(ctx.owner) is ctx.owner
    assert require_owner_or_admin(ctx.admin) is ctx.admin
    print("  owner-only gate ok")


def _test_project_manager_cannot_read_a_batch(session, ctx):
    """三个批次端点必须真的挂上该门禁，否则门禁再对也没用。"""
    gated = {"/api/employment-feedback/batches",
             "/api/employment-feedback/batches/{item_id}",
             "/api/employment-feedback/import/confirm"}
    for path in gated:
        route = next(r for r in facts_router.router.routes if r.path == path)
        assert route.dependencies, f"{path} 必须挂 owner-only 门禁"

    # 预览保留项目负责人权限：Phase 2 已按逐行范围阻断实现（§13.2 只排除批次确认）。
    preview = next(r for r in facts_router.router.routes
                   if r.path == "/api/employment-feedback/import/preview")
    assert preview.dependencies, "预览仍需企业/平台角色门禁"
    print("  batch routes carry the gate ok")


def _test_owner_still_sees_batches(session, ctx):
    rows = list_batches(ctx.owner, session)
    assert len(rows) == 1, rows
    assert get_batch(ctx.batch.id, ctx.owner, session).id == ctx.batch.id
    print("  owner still sees batches ok")


def _test_admin_still_sees_batches(session, ctx):
    assert len(list_batches(ctx.admin, session)) == 1
    print("  admin still sees batches ok")


if __name__ == "__main__":
    run()
