# 保险公司独立工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give insurance companies (保司) their own login portal — mirroring the existing salesperson portal — with a real `Insurer` entity, cross-insurer data isolation, and seven feature modules: profile editing (submit→admin-approve), position underwriting, policy document upload, settlement finance view, invoices, insured-person exception flagging, and claims management.

**Architecture:** New `Insurer` table replaces the free-text `InsurancePlan.insurer` / `InsurerAccountLink.insurer` strings as the source of truth for identity (both keep their string column for display, gain a nullable `insurer_id` FK). A new `User.role == "insurer"` account type, with `User.insurer_id` pointing at exactly one `Insurer`, logs in through a new `portal=insurer` branch and lands on a standalone `InsurerPortalView.vue` (not wrapped in `AppShell`, same pattern as `AgentPortalView.vue`). Six of the seven modules narrow existing admin-only endpoints (`positions.py`, `reports.py`, `claims.py`, `invoices.py`) to also accept `role=='insurer'` plus a per-request ownership check against `insurer_id`; the profile and settlement modules get one new dedicated router, `insurer_portal.py`.

**Tech Stack:** FastAPI + SQLAlchemy + Alembic (backend), Vue 3 + Element Plus + Pinia (web). No miniprogram changes (spec explicitly excludes it).

## Global Constraints

- Insurer accounts must only see/operate on data belonging to their own `insurer_id` — every endpoint touched in this plan needs an explicit ownership check, not just a role check. This is the core security requirement of the spec.
- Insurer profile edits (name/contact/phone) go through the existing "提交→平台审核" two-stage pattern (mirrors 岗位定类): a `PATCH` writes only `pending_*` fields; a separate admin review endpoint commits or discards them.
- Insurers can only **flag** insured-person records as abnormal (write a reason) — they can never directly mutate participation status (`InsuredPerson.status`, effective/termination dates). This boundary is enforced by giving the flag its own endpoint, never widening `PATCH /insured/{id}` or `PATCH /insured/{id}/status`.
- Financial views shown to insurers must hide platform-internal profit/margin/commission fields (reuse `strip_internal_pricing`'s existing per-role blacklist mechanism in `backend/services/pricing.py`), while still showing settlement price and premium — the opposite cut from the `enterprise` role.
- New Alembic migration must chain from the current head `6846c4ee59e2` and must be verified against real PostgreSQL with `python3 scripts/pg_migration_check.py` before merging (CLAUDE.md hard requirement — SQLite-only testing is explicitly insufficient).
- No miniprogram changes in this plan.
- Follow existing patterns exactly: `require_role()` / `assert_employer_access()`-style dependency functions in `backend/core/rbac.py` and `backend/services/`, allow-list response schemas where the existing salesperson portal already does this (`backend/schemas/agent_portal.py`), signed short-lived download tokens for any file already using them (`core/file_tokens.py`) — don't introduce new patterns where an existing one already covers the need.

---

## Task 1: `Insurer` entity, migration, and model extensions

**Files:**
- Create: `backend/models/insurer.py`
- Modify: `backend/models/__init__.py`
- Modify: `backend/models/user.py`
- Modify: `backend/models/plan.py`
- Modify: `backend/models/finance_accounts.py`
- Modify: `backend/models/insured.py`
- Create: `backend/migrations_alembic/versions/b4f19a7d2e63_add_insurer_entity.py`

**Interfaces:**
- Produces: `Insurer` model (`id, name, contact, phone, status, pending_name, pending_contact, pending_phone, pending_submitted_at, created_at`), `User.insurer_id`, `InsurancePlan.insurer_id`, `InsurerAccountLink.insurer_id`, `InsuredPerson.insurer_flag_reason` / `insurer_flagged_at` / `insurer_flagged_by`. All later tasks read/write these columns.

- [ ] **Step 1: Create the `Insurer` model**

`backend/models/insurer.py`:

```python
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base


class Insurer(Base):
    # 7-24 保司工作台设计翻转了 7-15《保司分账户充值与审核》"不引入独立 Insurer
    # 实体表"的决定：字符串关联做不到可靠的数据隔离，而这正是保司账号登录后
    # "看不到别的保司数据"这条安全边界的前提。name/contact/phone 是当前生效值；
    # pending_* 是保司提交、平台审核通过前的暂存值，两段式生效见
    # routers/insurers.py 的 review_insurer_edit。
    __tablename__ = "insurers"
    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    contact: Mapped[str] = mapped_column(String(80), default="")
    phone: Mapped[str] = mapped_column(String(30), default="")
    status: Mapped[str] = mapped_column(String(20), default="active")
    pending_name: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    pending_contact: Mapped[Optional[str]] = mapped_column(String(80), nullable=True)
    pending_phone: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    pending_submitted_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=lambda: datetime.now(timezone.utc))
```

- [ ] **Step 2: Register `Insurer` in `models/__init__.py`**

Add `from .insurer import Insurer` right after the `from .user import User` line, and add `"Insurer",` to `__all__` right after `"User",`.

- [ ] **Step 3: Extend `User` with `insurer_id`**

In `backend/models/user.py`, change the import line:

```python
from sqlalchemy import Boolean, CheckConstraint, DateTime, ForeignKey, Integer, String
```

Add this column right after `enterprise_role`:

```python
    # 仅 role='insurer' 账号使用。和 salesperson（账号本身就是业务员实体）不同，
    # Insurer 已经是独立实体表，所以保司账号是"User 通过 insurer_id 关联到一个
    # 已存在的 Insurer"，不是"User 本身就是保司记录"。
    insurer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurers.id"), nullable=True)
```

- [ ] **Step 4: Extend `InsurancePlan` with `insurer_id`**

In `backend/models/plan.py`, change imports to:

```python
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import DateTime, Float, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from ..core.db import Base
```

Add right after the `insurer` column in `InsurancePlan`:

```python
    # 迁移时按 insurer 字符串精确匹配回填；insurer 字符串字段保留不删，作为
    # 展示层过渡（见 2026-07-24 保司工作台设计）。
    insurer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurers.id"), nullable=True)
```

- [ ] **Step 5: Extend `InsurerAccountLink` with `insurer_id`**

In `backend/models/finance_accounts.py`, add right after the `insurer` column in `InsurerAccountLink`:

```python
    insurer_id: Mapped[Optional[int]] = mapped_column(ForeignKey("insurers.id"), nullable=True)
```

- [ ] **Step 6: Extend `InsuredPerson` with the flag fields**

In `backend/models/insured.py`, add right after the `status` column in `InsuredPerson`:

```python
    # 保司标注的异常原因（"参保/停保信息有问题"），空字符串表示当前没有标注。
    # 只能由 role='insurer' 通过 PATCH /insured/{id}/insurer-flag 写入/清空，
    # 企业端和平台端的参保状态本身不受影响——见 2026-07-24 设计文档"范围边界"。
    insurer_flag_reason: Mapped[str] = mapped_column(Text, default="")
    insurer_flagged_at: Mapped[Optional[datetime]] = mapped_column(DateTime, nullable=True)
    insurer_flagged_by: Mapped[Optional[int]] = mapped_column(ForeignKey("users.id"), nullable=True)
```

- [ ] **Step 7: Write the migration**

`backend/migrations_alembic/versions/b4f19a7d2e63_add_insurer_entity.py`:

```python
"""add insurer entity, backfill from insurer strings, insured-person flag

Revision ID: b4f19a7d2e63
Revises: 6846c4ee59e2
Create Date: 2026-07-24

"""
from datetime import datetime, timezone
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'b4f19a7d2e63'
down_revision: Union[str, Sequence[str], None] = '6846c4ee59e2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    op.create_table(
        'insurers',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('name', sa.String(length=100), nullable=False),
        sa.Column('contact', sa.String(length=80), nullable=False, server_default=''),
        sa.Column('phone', sa.String(length=30), nullable=False, server_default=''),
        sa.Column('status', sa.String(length=20), nullable=False, server_default='active'),
        sa.Column('pending_name', sa.String(length=100), nullable=True),
        sa.Column('pending_contact', sa.String(length=80), nullable=True),
        sa.Column('pending_phone', sa.String(length=30), nullable=True),
        sa.Column('pending_submitted_at', sa.DateTime(), nullable=True),
        sa.Column('created_at', sa.DateTime(), nullable=False),
    )

    conn = op.get_bind()
    names: set[str] = set()
    for row in conn.execute(sa.text(
        "SELECT DISTINCT insurer FROM insurance_plans WHERE insurer IS NOT NULL AND insurer <> ''"
    )):
        names.add(row[0])
    for row in conn.execute(sa.text(
        "SELECT DISTINCT insurer FROM insurer_account_links WHERE insurer IS NOT NULL AND insurer <> ''"
    )):
        names.add(row[0])

    insurers_table = sa.table(
        'insurers',
        sa.column('name', sa.String),
        sa.column('contact', sa.String),
        sa.column('phone', sa.String),
        sa.column('status', sa.String),
        sa.column('created_at', sa.DateTime),
    )
    now = datetime.now(timezone.utc)
    for name in sorted(names):
        conn.execute(insurers_table.insert().values(
            name=name, contact='', phone='', status='active', created_at=now,
        ))

    op.add_column('insurance_plans', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('insurer_account_links', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('users', sa.Column('insurer_id', sa.Integer(), sa.ForeignKey('insurers.id'), nullable=True))
    op.add_column('insured_people', sa.Column('insurer_flag_reason', sa.Text(), nullable=False, server_default=''))
    op.add_column('insured_people', sa.Column('insurer_flagged_at', sa.DateTime(), nullable=True))
    op.add_column('insured_people', sa.Column('insurer_flagged_by', sa.Integer(), sa.ForeignKey('users.id'), nullable=True))

    conn.execute(sa.text(
        "UPDATE insurance_plans SET insurer_id = "
        "(SELECT id FROM insurers WHERE insurers.name = insurance_plans.insurer) "
        "WHERE insurer IS NOT NULL AND insurer <> ''"
    ))
    conn.execute(sa.text(
        "UPDATE insurer_account_links SET insurer_id = "
        "(SELECT id FROM insurers WHERE insurers.name = insurer_account_links.insurer) "
        "WHERE insurer IS NOT NULL AND insurer <> ''"
    ))


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_column('insured_people', 'insurer_flagged_by')
    op.drop_column('insured_people', 'insurer_flagged_at')
    op.drop_column('insured_people', 'insurer_flag_reason')
    op.drop_column('users', 'insurer_id')
    op.drop_column('insurer_account_links', 'insurer_id')
    op.drop_column('insurance_plans', 'insurer_id')
    op.drop_table('insurers')
```

- [ ] **Step 4: Run the migration against a throwaway local SQLite DB and verify**

```bash
cd /Users/madisonshen/Desktop/Demo
rm -f /tmp/insurer_migration_check.db
DATABASE_URL=sqlite:////tmp/insurer_migration_check.db python3 -m alembic upgrade head
DATABASE_URL=sqlite:////tmp/insurer_migration_check.db python3 -c "
import sqlite3
conn = sqlite3.connect('/tmp/insurer_migration_check.db')
cols = [r[1] for r in conn.execute('PRAGMA table_info(insurers)')]
assert cols == ['id','name','contact','phone','status','pending_name','pending_contact','pending_phone','pending_submitted_at','created_at'], cols
print('insurers table OK')
"
rm -f /tmp/insurer_migration_check.db
```

Expected: no errors, `insurers table OK` printed.

- [ ] **Step 5: Verify against real PostgreSQL (mandatory per CLAUDE.md)**

```bash
python3 scripts/pg_migration_check.py
```

Expected: script reports the migration applied cleanly on a throwaway Neon branch. Do not proceed past this task until this passes — SQLite-only testing has previously hidden real PostgreSQL failures in this repo (e.g. `server_default=sa.text("1")` on boolean columns).

- [ ] **Step 6: Compile check and commit**

```bash
python3 -m compileall -q backend
git add backend/models/insurer.py backend/models/__init__.py backend/models/user.py \
  backend/models/plan.py backend/models/finance_accounts.py backend/models/insured.py \
  backend/migrations_alembic/versions/b4f19a7d2e63_add_insurer_entity.py
git commit -m "feat: add Insurer entity, insurer_id backfill, insured-person flag columns"
```

---

## Task 2: RBAC, auth, and pricing role-blacklist wiring

**Files:**
- Modify: `backend/core/rbac.py`
- Modify: `backend/core/security.py`
- Modify: `backend/routers/auth.py`
- Modify: `backend/schemas/auth.py`
- Modify: `backend/services/pricing.py`
- Create: `backend/services/insurer_scope.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/insurer_rbac_test.py`

**Interfaces:**
- Consumes: `User.insurer_id`, `InsurancePlan.insurer_id` (Task 1).
- Produces: `require_insurer_scope` dependency (used by every insurer-portal endpoint in later tasks), `assert_plan_belongs_to_insurer(session, user, plan_id)`, `insurer_plan_ids(session, insurer_id) -> set[int]`, `claim_insurer_id(claim, session) -> Optional[int]` (all in `backend/services/insurer_scope.py`), `_HIDDEN_PRICING_FIELDS_BY_ROLE["insurer"]` in `pricing.py`.

- [ ] **Step 1: Add `require_insurer_scope` to `rbac.py`**

Append to `backend/core/rbac.py`:

```python
def require_insurer_scope(user: User = Depends(current_user)) -> User:
    if user.role != "insurer" or not user.insurer_id:
        raise HTTPException(status_code=403, detail="仅保司账号可访问")
    return user
```

- [ ] **Step 2: Widen the global role allowlist and add insurer path scoping in `security.py`**

In `backend/core/security.py`, change the allowed-roles set:

```python
    if user.role not in {"admin","enterprise","salesperson","insurer"}: raise HTTPException(status_code=403, detail="该账号暂未开通管理端权限")
```

Add, right after `SALESPERSON_ALLOWED_PREFIXES`:

```python
# The insurer portal is not fully self-contained like the salesperson one — it
# reuses six existing shared routers (positions, policies, claims, invoices)
# with narrowed per-request permission, alongside its own /insurer-portal/*
# endpoints. So this is a wider allowlist than SALESPERSON_ALLOWED_*, but every
# path on it is either already role-narrowed at the route level (positions,
# policies, claims, invoices — see Tasks 6/7/9/11) or lives under
# /insurer-portal/ where every route requires require_insurer_scope.
INSURER_ALLOWED_PATHS = {"/api/auth/me", "/api/auth/password", "/api/positions", "/api/claims", "/api/invoices", "/api/policies"}
INSURER_ALLOWED_PREFIXES = ("/api/insurer-portal/", "/api/positions/", "/api/policies/", "/api/claims/")
```

Add a guard clause right after the salesperson guard clause (same `current_user` function):

```python
    if user.role == "insurer" and request.url.path not in INSURER_ALLOWED_PATHS and not request.url.path.startswith(INSURER_ALLOWED_PREFIXES): raise HTTPException(status_code=403, detail="保司账号仅可访问保司工作台相关接口")
```

- [ ] **Step 3: Add the `insurer` portal branch to login**

In `backend/schemas/auth.py`, widen the literal:

```python
class LoginIn(BaseModel): username: str; password: str; portal: Literal["admin","enterprise","salesperson","insurer"] = "admin"
```

In `backend/routers/auth.py`, add right after the `salesperson` portal check in `login()`:

```python
    if data.portal == "insurer" and user.role != "insurer": raise HTTPException(403, "该账号不是保司账号")
```

- [ ] **Step 4: Add the insurer pricing-field blacklist**

In `backend/services/pricing.py`, add right after `_AGENT_INTERNAL_PRICING_FIELDS`:

```python
# 保司能看到结算价/保费（这正是他们的收款依据），但不能看到平台的返佣、利润、
# 差价——这是和 enterprise/salesperson 两份黑名单相反方向的裁剪：那两份藏起
# 平台的成本基准，这份藏起平台的"赚了多少"。见 2026-07-24 设计文档"范围边界"。
_INSURER_HIDDEN_PRICING_FIELDS = {
    'profit_amount', 'commission_mode', 'agent_commission_rate', 'agent_commission_amount',
    'platform_margin_amount', 'total_commission_rate', 'total_commission_amount',
    'total_commission_total', 'agent_commission_total', 'commission_rate',
}
```

And add `'insurer': _INSURER_HIDDEN_PRICING_FIELDS,` as a new entry in `_HIDDEN_PRICING_FIELDS_BY_ROLE`.

- [ ] **Step 5: Create the shared insurer-scope helper service**

`backend/services/insurer_scope.py`:

```python
"""Shared insurer-portal ownership checks (2026-07-24 design §范围边界).

Every module the insurer portal touches (positions, policies, claims,
invoices) needs the same question answered: does this record belong to the
caller's insurer_id? Centralized here so the join-chain logic (especially the
claim → policy → plan → insurer_id resolution, which must match
claim_payload()'s fallback exactly) is written once.
"""
from typing import Optional

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Claim, InsurancePlan, InsuredPerson, Policy, User


def assert_plan_belongs_to_insurer(session: Session, user: User, plan_id: Optional[int]) -> None:
    """role=='insurer' 专用越权检查：目标方案必须挂在自己 insurer_id 下。
    非 insurer 角色直接放行——这不是身份检查，是范围检查。"""
    if user.role != "insurer":
        return
    if not plan_id:
        raise HTTPException(403, "无权操作未指定保险方案的记录")
    plan = session.get(InsurancePlan, plan_id)
    if not plan or plan.insurer_id != user.insurer_id:
        raise HTTPException(403, "无权操作其他保险公司的方案")


def insurer_plan_ids(session: Session, insurer_id: int) -> set[int]:
    return set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))


def claim_insurer_id(claim: Claim, session: Session) -> Optional[int]:
    """理赔案件归属的 insurer_id。和 services/claims.py 里 claim_payload() 的保单
    解析链路保持一致：优先 claim.policy_id，为空则退回被保险人当前 policy_id。"""
    policy_id = claim.policy_id
    if not policy_id:
        person = session.get(InsuredPerson, claim.person_id)
        policy_id = person.policy_id if person else None
    if not policy_id:
        return None
    policy = session.get(Policy, policy_id)
    if not policy:
        return None
    plan = session.get(InsurancePlan, policy.plan_id)
    return plan.insurer_id if plan else None
```

- [ ] **Step 6: Export from `services/__init__.py`**

Add to `backend/services/__init__.py`:

```python
from .insurer_scope import assert_plan_belongs_to_insurer, claim_insurer_id, insurer_plan_ids
```

And add `"assert_plan_belongs_to_insurer", "claim_insurer_id", "insurer_plan_ids",` to `__all__`.

- [ ] **Step 7: Write the RBAC test**

`tests/insurer_rbac_test.py`:

```python
"""Insurer-role RBAC: login portal gating, path allowlist, pricing blacklist."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Insurer, User  # noqa: E402
from backend.services.pricing import strip_internal_pricing  # noqa: E402

client = TestClient(app)


def _make_insurer_user(name="人保", username="insurer_pingan"):
    with SessionLocal() as s:
        insurer = Insurer(name=name, contact="张经理", phone="13800000000")
        s.add(insurer); s.flush()
        user = User(username=username, password_hash=pwd.hash("test1234"), name="保司账号",
                    role="insurer", insurer_id=insurer.id)
        s.add(user); s.commit(); s.refresh(user); s.refresh(insurer)
        return insurer.id, user.id


def test_insurer_login_requires_insurer_portal():
    _make_insurer_user()
    resp = client.post("/api/auth/login", json={"username": "insurer_pingan", "password": "test1234", "portal": "admin"})
    assert resp.status_code == 403


def test_insurer_login_succeeds_on_insurer_portal():
    _make_insurer_user(username="insurer_login_ok")
    resp = client.post("/api/auth/login", json={"username": "insurer_login_ok", "password": "test1234", "portal": "insurer"})
    assert resp.status_code == 200
    assert "access_token" in resp.json()


def test_insurer_blocked_from_out_of_scope_endpoint():
    _make_insurer_user(username="insurer_scope_check")
    login = client.post("/api/auth/login", json={"username": "insurer_scope_check", "password": "test1234", "portal": "insurer"})
    token = login.json()["access_token"]
    resp = client.get("/api/enterprises", headers={"Authorization": f"Bearer {token}"})
    assert resp.status_code == 403


def test_strip_internal_pricing_hides_profit_shows_settlement_price():
    class FakeUser:
        role = "insurer"
    data = {"policy_floor_price": 100.0, "profit_amount": 20.0, "agent_commission_amount": 5.0, "insurance_base_price": 130.0}
    result = strip_internal_pricing(data, FakeUser())
    assert result["policy_floor_price"] == 100.0
    assert result["insurance_base_price"] == 130.0
    assert "profit_amount" not in result
    assert "agent_commission_amount" not in result
```

- [ ] **Step 8: Run the test**

```bash
python3 tests/insurer_rbac_test.py 2>&1 | tail -5 || python3 -m pytest tests/insurer_rbac_test.py -v
```

Expected: all 4 tests pass. (This repo's smoke tests are plain scripts, not pytest-driven — check `tests/security_smoke.py` for the exact run convention this repo uses and match it; if it's a `python3 tests/x.py` script with asserts and no pytest collection, convert the four `test_*` functions above into a `main()` that calls each and prints `OK` per repo convention before committing.)

- [ ] **Step 9: Commit**

```bash
git add backend/core/rbac.py backend/core/security.py backend/routers/auth.py backend/schemas/auth.py \
  backend/services/pricing.py backend/services/insurer_scope.py backend/services/__init__.py tests/insurer_rbac_test.py
git commit -m "feat: wire insurer role through RBAC, login portal, and pricing blacklist"
```

---

## Task 3: Admin 保司管理 backend — CRUD, pending-edit review, merge tool

**Files:**
- Create: `backend/schemas/insurer.py`
- Modify: `backend/schemas/__init__.py`
- Create: `backend/routers/insurers.py`
- Modify: `backend/app.py`
- Test: `tests/insurer_admin_test.py`

**Interfaces:**
- Consumes: `Insurer` model, `require_role` (Task 1/existing).
- Produces: `GET/POST /api/insurers`, `PATCH /api/insurers/{id}`, `GET /api/insurers/pending-edits`, `POST /api/insurers/{id}/review-edit`, `POST /api/insurers/merge`.

- [ ] **Step 1: Write the schemas**

`backend/schemas/insurer.py`:

```python
from typing import Optional

from pydantic import BaseModel, Field


class InsurerIn(BaseModel):
    name: str = Field(min_length=1)
    contact: str = ""
    phone: str = ""


class InsurerUpdate(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    phone: Optional[str] = None


class InsurerEditReviewIn(BaseModel):
    approve: bool
    reject_reason: str = ""


class InsurerMergeIn(BaseModel):
    source_ids: list[int] = Field(min_length=1)
    target_id: int


class InsurerProfileIn(BaseModel):
    name: Optional[str] = None
    contact: Optional[str] = None
    phone: Optional[str] = None
```

- [ ] **Step 2: Register in `schemas/__init__.py`**

Add near the other imports:

```python
from .insurer import InsurerIn, InsurerUpdate, InsurerEditReviewIn, InsurerMergeIn, InsurerProfileIn
```

Add to `__all__`: `"InsurerIn", "InsurerUpdate", "InsurerEditReviewIn", "InsurerMergeIn", "InsurerProfileIn",`.

- [ ] **Step 3: Write the admin router**

`backend/routers/insurers.py`:

```python
"""Admin-only 保司主体管理 — CRUD over Insurer, pending-edit review, merge tool.

Separate from routers/plans.py (which manages InsurancePlan, the 保险产品 the
old PlansAdminView.vue page calls "保险公司" in its title): this router
manages the Insurer *entity* introduced 2026-07-24 to back the insurer login
portal's data isolation.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..core.audit import audit
from ..core.db import db
from ..core.rbac import require_role
from ..core.security import current_user
from ..models import Insurer, InsurancePlan, InsurerAccountLink, User
from ..schemas.insurer import InsurerEditReviewIn, InsurerIn, InsurerMergeIn, InsurerUpdate
from ..services import serialize

router = APIRouter(prefix="/api", tags=["insurers"])

_ADMIN = require_role("admin", detail="仅平台管理员可管理保险公司")


@router.get("/insurers", dependencies=[Depends(_ADMIN)])
def insurers(session: Session = Depends(db)):
    return [serialize(x) for x in session.scalars(select(Insurer).order_by(Insurer.id.desc()))]


@router.post("/insurers", dependencies=[Depends(_ADMIN)])
def add_insurer(data: InsurerIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if session.scalar(select(Insurer.id).where(Insurer.name == data.name).limit(1)):
        raise HTTPException(409, "该保司名称已存在，如需处理重复录入请使用合并保司工具")
    item = Insurer(name=data.name.strip(), contact=data.contact.strip(), phone=data.phone.strip())
    session.add(item); session.commit(); session.refresh(item)
    audit(session, user, "create", "insurer", str(item.id))
    return serialize(item)


@router.patch("/insurers/{item_id}", dependencies=[Depends(_ADMIN)])
def update_insurer(item_id: int, data: InsurerUpdate, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, item_id)
    if not item: raise HTTPException(404, "保险公司不存在")
    for key, value in data.model_dump(exclude_unset=True).items():
        if value is not None: setattr(item, key, value.strip() if isinstance(value, str) else value)
    session.commit(); audit(session, user, "update", "insurer", str(item.id))
    return serialize(item)


@router.get("/insurers/pending-edits", dependencies=[Depends(_ADMIN)])
def pending_insurer_edits(session: Session = Depends(db)):
    stmt = select(Insurer).where(Insurer.pending_submitted_at.isnot(None)).order_by(Insurer.pending_submitted_at.asc())
    return [serialize(x) for x in session.scalars(stmt)]


@router.post("/insurers/{item_id}/review-edit", dependencies=[Depends(_ADMIN)])
def review_insurer_edit(item_id: int, data: InsurerEditReviewIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, item_id)
    if not item: raise HTTPException(404, "保险公司不存在")
    if item.pending_submitted_at is None: raise HTTPException(409, "该保司当前没有待审核的信息变更")
    if data.approve:
        if item.pending_name is not None: item.name = item.pending_name
        if item.pending_contact is not None: item.contact = item.pending_contact
        if item.pending_phone is not None: item.phone = item.pending_phone
    item.pending_name = None
    item.pending_contact = None
    item.pending_phone = None
    item.pending_submitted_at = None
    session.commit()
    audit(session, user, "review", "insurer", str(item.id), "approved" if data.approve else f"rejected:{data.reject_reason}")
    return serialize(item)


@router.post("/insurers/merge", dependencies=[Depends(_ADMIN)])
def merge_insurers(data: InsurerMergeIn, user: User = Depends(current_user), session: Session = Depends(db)):
    if data.target_id in data.source_ids:
        raise HTTPException(400, "保留目标不能同时出现在被合并列表里")
    target = session.get(Insurer, data.target_id)
    if not target: raise HTTPException(404, "保留目标保司不存在")
    sources = []
    for source_id in data.source_ids:
        source = session.get(Insurer, source_id)
        if not source: raise HTTPException(404, f"待合并保司 {source_id} 不存在")
        sources.append(source)
    source_ids = [s.id for s in sources]
    session.query(InsurancePlan).filter(InsurancePlan.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    session.query(InsurerAccountLink).filter(InsurerAccountLink.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    session.query(User).filter(User.insurer_id.in_(source_ids)).update(
        {"insurer_id": target.id}, synchronize_session=False)
    for source in sources:
        session.delete(source)
    session.commit()
    audit(session, user, "merge", "insurer", str(target.id), f"merged={source_ids}")
    return serialize(target)
```

- [ ] **Step 4: Register the router in `app.py`**

Add import after `from .routers.ocr import router as ocr_router`:

```python
from .routers.insurers import router as insurers_router
```

Add include after `app.include_router(ocr_router)`:

```python
app.include_router(insurers_router)
```

- [ ] **Step 5: Write the admin test**

`tests/insurer_admin_test.py`:

```python
"""Admin 保司管理: CRUD, pending-edit two-stage approval, merge tool."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Insurer, InsurancePlan, User  # noqa: E402

client = TestClient(app)


def _admin_token():
    with SessionLocal() as s:
        if not s.query(User).filter(User.username == "admin_insurer_test").first():
            s.add(User(username="admin_insurer_test", password_hash=pwd.hash("admin1234"), name="平台", role="admin"))
            s.commit()
    resp = client.post("/api/auth/login", json={"username": "admin_insurer_test", "password": "admin1234", "portal": "admin"})
    return resp.json()["access_token"]


def test_create_and_list_insurer():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    resp = client.post("/api/insurers", json={"name": "太平洋保险", "contact": "李经理", "phone": "13900000000"}, headers=headers)
    assert resp.status_code == 200
    listing = client.get("/api/insurers", headers=headers)
    assert any(x["name"] == "太平洋保险" for x in listing.json())


def test_pending_edit_two_stage_approval():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    created = client.post("/api/insurers", json={"name": "人寿保险", "contact": "老联系人", "phone": "111"}, headers=headers).json()
    insurer_id = created["id"]
    with SessionLocal() as s:
        item = s.get(Insurer, insurer_id)
        item.pending_name = "人寿保险(改名)"
        item.pending_contact = "新联系人"
        item.pending_phone = "222"
        from datetime import datetime, timezone
        item.pending_submitted_at = datetime.now(timezone.utc)
        s.commit()
    unchanged = client.get("/api/insurers", headers=headers).json()
    row = next(x for x in unchanged if x["id"] == insurer_id)
    assert row["name"] == "人寿保险"
    assert row["pending_name"] == "人寿保险(改名)"
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": True}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "人寿保险(改名)"
    assert resp.json()["pending_name"] is None


def test_pending_edit_reject_leaves_name_unchanged():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    created = client.post("/api/insurers", json={"name": "友邦保险"}, headers=headers).json()
    insurer_id = created["id"]
    with SessionLocal() as s:
        item = s.get(Insurer, insurer_id)
        item.pending_name = "友邦保险(错误改名)"
        from datetime import datetime, timezone
        item.pending_submitted_at = datetime.now(timezone.utc)
        s.commit()
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": False, "reject_reason": "信息有误"}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["name"] == "友邦保险"
    assert resp.json()["pending_name"] is None


def test_merge_insurers_repoints_plans():
    token = _admin_token()
    headers = {"Authorization": f"Bearer {token}"}
    a = client.post("/api/insurers", json={"name": "人保"}, headers=headers).json()
    b = client.post("/api/insurers", json={"name": "人保保险"}, headers=headers).json()
    with SessionLocal() as s:
        plan = InsurancePlan(insurer="人保保险", name="测试方案", insurer_id=b["id"])
        s.add(plan); s.commit(); s.refresh(plan)
        plan_id = plan.id
    resp = client.post("/api/insurers/merge", json={"source_ids": [b["id"]], "target_id": a["id"]}, headers=headers)
    assert resp.status_code == 200
    with SessionLocal() as s:
        assert s.get(InsurancePlan, plan_id).insurer_id == a["id"]
        assert s.get(Insurer, b["id"]) is None
```

- [ ] **Step 6: Run tests**

```bash
python3 tests/insurer_admin_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_admin_test.py -v
```

Expected: all 4 tests pass (adapt to this repo's script-vs-pytest convention as in Task 2 Step 8).

- [ ] **Step 7: Compile check and commit**

```bash
python3 -m compileall -q backend
git add backend/schemas/insurer.py backend/schemas/__init__.py backend/routers/insurers.py backend/app.py tests/insurer_admin_test.py
git commit -m "feat: admin 保司管理 CRUD, pending-edit review, merge tool"
```

---

## Task 4: Admin 保司管理 web page

**Files:**
- Modify: `web/src/api/types.ts`
- Create: `web/src/api/insurers.ts`
- Create: `web/src/views/insurers/InsurerManagementView.vue`
- Modify: `web/src/router/routes.ts`
- Modify: `backend/app.py` (add web route to `_FRONTEND_ROUTES`)

**Interfaces:**
- Consumes: `GET/POST /api/insurers`, `PATCH /api/insurers/{id}`, `GET /api/insurers/pending-edits`, `POST /api/insurers/{id}/review-edit`, `POST /api/insurers/merge` (Task 3).
- Produces: `Insurer` TS interface, `listInsurers/createInsurer/updateInsurer/listPendingInsurerEdits/reviewInsurerEdit/mergeInsurers` API functions.

- [ ] **Step 1: Add the `Insurer` TS type**

In `web/src/api/types.ts`, add after the `ActualEmployer` interface:

```ts
export interface Insurer {
  id: number
  name: string
  contact: string
  phone: string
  status: string
  pending_name: string | null
  pending_contact: string | null
  pending_phone: string | null
  pending_submitted_at: string | null
  created_at: string
}
```

- [ ] **Step 2: Write the API client**

`web/src/api/insurers.ts`:

```ts
import { client } from './client'
import type { Insurer } from './types'

export function listInsurers() {
  return client.get<Insurer[]>('/insurers').then((response) => response.data)
}

export function createInsurer(data: { name: string; contact?: string; phone?: string }) {
  return client.post<Insurer>('/insurers', data).then((response) => response.data)
}

export function updateInsurer(id: number, data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>(`/insurers/${id}`, data).then((response) => response.data)
}

export function listPendingInsurerEdits() {
  return client.get<Insurer[]>('/insurers/pending-edits').then((response) => response.data)
}

export function reviewInsurerEdit(id: number, data: { approve: boolean; reject_reason?: string }) {
  return client.post<Insurer>(`/insurers/${id}/review-edit`, data).then((response) => response.data)
}

export function mergeInsurers(data: { source_ids: number[]; target_id: number }) {
  return client.post<Insurer>('/insurers/merge', data).then((response) => response.data)
}
```

- [ ] **Step 3: Write the admin page**

`web/src/views/insurers/InsurerManagementView.vue`:

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as insurersApi from '@/api/insurers'
import type { Insurer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'

const loading = ref(true)
const list = ref<Insurer[]>([])
const pendingEdits = ref<Insurer[]>([])

async function load() {
  loading.value = true
  try {
    const [all, pending] = await Promise.all([insurersApi.listInsurers(), insurersApi.listPendingInsurerEdits()])
    list.value = all
    pendingEdits.value = pending
  } finally {
    loading.value = false
  }
}
onMounted(load)

const editingId = ref<number | null>(null)
const form = reactive({ name: '', contact: '', phone: '' })
function resetForm() {
  editingId.value = null
  Object.assign(form, { name: '', contact: '', phone: '' })
}
function editInsurer(item: Insurer) {
  editingId.value = item.id
  Object.assign(form, { name: item.name, contact: item.contact, phone: item.phone })
}
const saving = ref(false)
async function submitForm() {
  if (!form.name.trim()) { ElMessage.error('请填写保险公司名称'); return }
  saving.value = true
  try {
    if (editingId.value) await insurersApi.updateInsurer(editingId.value, form)
    else await insurersApi.createInsurer(form)
    ElMessage.success(editingId.value ? '已保存' : '已创建')
    resetForm()
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    saving.value = false
  }
}

async function approveEdit(item: Insurer) {
  try {
    await ElMessageBox.confirm(`确认将「${item.name}」更新为「${item.pending_name}」？`, '审核确认', { type: 'warning' })
  } catch { return }
  await insurersApi.reviewInsurerEdit(item.id, { approve: true })
  ElMessage.success('已通过')
  load()
}
async function rejectEdit(item: Insurer) {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回变更', { inputPattern: /.+/, inputErrorMessage: '请填写驳回原因' })
    await insurersApi.reviewInsurerEdit(item.id, { approve: false, reject_reason: value })
    ElMessage.success('已驳回')
    load()
  } catch { /* cancelled */ }
}

const mergeVisible = ref(false)
const mergeTarget = ref<number | null>(null)
const mergeSources = ref<number[]>([])
function openMerge() {
  mergeTarget.value = null
  mergeSources.value = []
  mergeVisible.value = true
}
const mergeCandidates = computed(() => list.value.filter((x) => x.id !== mergeTarget.value))
async function submitMerge() {
  if (!mergeTarget.value || !mergeSources.value.length) { ElMessage.error('请选择保留目标和待合并保司'); return }
  try {
    await ElMessageBox.confirm('合并后被合并保司名下的产品、账户绑定、保司账号都会改指到保留目标，且被合并记录会被删除，此操作不可逆。确认继续？', '合并确认', { type: 'warning' })
  } catch { return }
  try {
    await insurersApi.mergeInsurers({ source_ids: mergeSources.value, target_id: mergeTarget.value })
    ElMessage.success('已合并')
    mergeVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>

<template>
  <div v-loading="loading" class="insurer-management-view">
    <PageCard title="录入保险公司" hint="保司账号登录后只能看到、只能操作自己名下的数据，名称需与历史录入保持一致才能自动关联">
      <el-form :model="form" label-width="120px" class="insurer-form">
        <el-form-item label="保险公司名称" required><el-input v-model="form.name" placeholder="如：中国人保财险" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submitForm">{{ editingId ? '保存修改' : '保存' }}</el-button>
          <el-button v-if="editingId" @click="resetForm">取消编辑</el-button>
        </el-form-item>
      </el-form>
    </PageCard>

    <PageCard title="保险公司列表" :count="list.length">
      <template #actions>
        <el-button @click="openMerge">合并保司</el-button>
      </template>
      <el-table :data="list" size="small">
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="contact" label="联系人" width="120" />
        <el-table-column prop="phone" label="联系电话" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '启用' : '暂停' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="editInsurer(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </PageCard>

    <PageCard v-if="pendingEdits.length" title="待审核的保司信息变更" :count="pendingEdits.length">
      <el-table :data="pendingEdits" size="small">
        <el-table-column label="当前名称" min-width="140"><template #default="{ row }">{{ row.name }}</template></el-table-column>
        <el-table-column label="申请修改为" min-width="140">
          <template #default="{ row }">
            <div>{{ row.pending_name || row.name }}</div>
            <small class="muted">{{ row.pending_contact || row.contact }} · {{ row.pending_phone || row.phone }}</small>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="approveEdit(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="rejectEdit(row)">驳回</el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <el-dialog v-model="mergeVisible" title="合并保司" width="480px">
      <el-form label-width="110px">
        <el-form-item label="保留目标">
          <el-select v-model="mergeTarget" placeholder="选择保留的保司" style="width: 100%">
            <el-option v-for="item in list" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="待合并">
          <el-select v-model="mergeSources" multiple placeholder="选择将被合并、删除的保司" style="width: 100%">
            <el-option v-for="item in mergeCandidates" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="danger" @click="submitMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.insurer-management-view {
  display: grid;
  gap: 18px;
}
.insurer-form {
  padding: 0 20px 20px;
  max-width: 480px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
```

- [ ] **Step 4: Register the route**

In `web/src/router/routes.ts`, add after the `/insurers` route:

```ts
  { path: '/insurer-management', name: 'insurerManagement', component: () => import('@/views/insurers/InsurerManagementView.vue'), meta: { title: '保司主体管理', group: '产品与保司', adminOnly: true } },
```

- [ ] **Step 5: Add the path to the backend SPA whitelist**

In `backend/app.py`, add `"/insurer-management",` to the `_FRONTEND_ROUTES` set (next to `"/insurers",`).

- [ ] **Step 6: Type-check and build**

```bash
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: no type errors, build succeeds.

- [ ] **Step 7: Commit**

```bash
git add web/src/api/types.ts web/src/api/insurers.ts web/src/views/insurers/InsurerManagementView.vue \
  web/src/router/routes.ts backend/app.py
git commit -m "feat: admin 保司主体管理 web page"
```

---

## Task 5: Insurer login portal shell + 基本信息编辑 module

**Files:**
- Create: `backend/routers/insurer_portal.py`
- Modify: `backend/app.py`
- Modify: `backend/schemas/insurer.py` (already has `InsurerProfileIn` from Task 3)
- Create: `web/src/api/insurerPortal.ts`
- Create: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/router/routes.ts`
- Modify: `web/src/App.vue`
- Modify: `web/src/layouts/AppShell.vue`
- Modify: `web/src/views/auth/LoginView.vue`
- Modify: `web/src/api/types.ts`
- Modify: `web/src/stores/auth.ts`
- Test: `tests/insurer_profile_test.py`

**Interfaces:**
- Consumes: `require_insurer_scope` (Task 2), `Insurer` model (Task 1).
- Produces: `GET/PATCH /api/insurer-portal/profile`. `InsurerPortalView.vue` with a `基本信息` tab — later tasks (6-11) each add one more `el-tab-pane` plus its script section to this same file.

- [ ] **Step 1: Write the profile endpoints**

`backend/routers/insurer_portal.py`:

```python
"""Insurer portal APIs (2026-07-24 design). Every endpoint here requires
require_insurer_scope and derives insurer_id from the JWT — same identity
discipline as agent_portal.py: a supplied insurer_id in a query/body is never
honoured, only the authenticated user's own insurer_id.
"""
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..core.db import db
from ..core.rbac import require_insurer_scope
from ..core.security import current_user
from ..models import Insurer, User
from ..schemas.insurer import InsurerProfileIn
from ..services import serialize

router = APIRouter(prefix="/api/insurer-portal", tags=["insurer-portal"])

_INSURER = require_insurer_scope


@router.get("/profile", dependencies=[Depends(_INSURER)])
def profile(user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, user.insurer_id)
    if not item: raise HTTPException(404, "保司信息不存在")
    return serialize(item)


@router.patch("/profile", dependencies=[Depends(_INSURER)])
def submit_profile_edit(data: InsurerProfileIn, user: User = Depends(current_user), session: Session = Depends(db)):
    item = session.get(Insurer, user.insurer_id)
    if not item: raise HTTPException(404, "保司信息不存在")
    values = data.model_dump(exclude_unset=True)
    if not values: raise HTTPException(400, "请至少填写一项要修改的信息")
    item.pending_name = values.get("name", item.name)
    item.pending_contact = values.get("contact", item.contact)
    item.pending_phone = values.get("phone", item.phone)
    item.pending_submitted_at = datetime.now(timezone.utc)
    session.commit()
    return serialize(item)
```

- [ ] **Step 2: Register the router**

In `backend/app.py`, add import after `from .routers.insurers import router as insurers_router`:

```python
from .routers.insurer_portal import router as insurer_portal_router
```

Add include after `app.include_router(insurers_router)`:

```python
app.include_router(insurer_portal_router)
```

- [ ] **Step 3: Add the `/insurer-portal` SPA path**

In `_FRONTEND_ROUTES` in `backend/app.py`, add `"/insurer-portal",`.

- [ ] **Step 4: Widen the `Role` type and login signature**

In `web/src/api/types.ts`, change:

```ts
export type Role = 'admin' | 'enterprise' | 'salesperson' | 'insurer'
```

Add `insurer_id: number | null` to the `User` interface, right after `enterprise_role`.

In `web/src/stores/auth.ts`, widen the `login` signature:

```ts
  async function login(username: string, password: string, portal: 'admin' | 'enterprise' | 'salesperson' | 'insurer') {
```

- [ ] **Step 5: Write the insurer-portal API client**

`web/src/api/insurerPortal.ts`:

```ts
import { client } from './client'
import type { Insurer } from './types'

export function getInsurerProfile() {
  return client.get<Insurer>('/insurer-portal/profile').then((response) => response.data)
}

export function submitInsurerProfileEdit(data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>('/insurer-portal/profile', data).then((response) => response.data)
}
```

- [ ] **Step 6: Write the portal shell + 基本信息 tab**

`web/src/views/insurer-portal/InsurerPortalView.vue`:

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getInsurerProfile, submitInsurerProfileEdit } from '@/api/insurerPortal'
import type { Insurer } from '@/api/types'
import PageCard from '@/components/PageCard.vue'
import PasswordChangeDialog from '@/components/PasswordChangeDialog.vue'

const router = useRouter()
const auth = useAuthStore()

const tab = ref('profile')
const loading = ref(true)
const passwordDialogVisible = ref(false)

const profile = ref<Insurer | null>(null)
const profileForm = reactive({ name: '', contact: '', phone: '' })
const profileSaving = ref(false)

async function loadProfile() {
  profile.value = await getInsurerProfile()
  if (profile.value) {
    Object.assign(profileForm, { name: profile.value.name, contact: profile.value.contact, phone: profile.value.phone })
  }
}

async function load() {
  loading.value = true
  try {
    if (!auth.user) await auth.loadProfile()
    if (auth.user?.role !== 'insurer') {
      router.replace({ name: 'home' })
      return
    }
    await loadProfile()
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function submitProfileEdit() {
  if (!profileForm.name.trim()) { ElMessage.error('请填写保险公司名称'); return }
  profileSaving.value = true
  try {
    profile.value = await submitInsurerProfileEdit(profileForm)
    ElMessage.success('已提交，等待平台审核后生效')
  } catch (e) {
    ElMessage.error((e as Error).message)
  } finally {
    profileSaving.value = false
  }
}

function logout() {
  ElMessageBox.confirm('确定要退出登录吗？', '退出登录', { type: 'warning' }).then(() => {
    auth.logout()
    router.replace({ name: 'login' })
  })
}
</script>

<template>
  <div class="insurer-portal">
    <header class="portal-header">
      <div class="portal-brand">响帮帮无忧保 · 保司工作台</div>
      <div class="portal-actions">
        <span class="portal-user">{{ auth.user?.name }}</span>
        <el-button size="small" @click="passwordDialogVisible = true">修改密码</el-button>
        <el-button size="small" @click="logout">退出登录</el-button>
      </div>
    </header>

    <main class="portal-body" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="基本信息" name="profile">
          <PageCard title="保司基本信息" hint="修改需经平台审核通过后才会生效">
            <el-form :model="profileForm" label-width="120px" style="max-width: 480px; padding: 0 20px 20px">
              <el-form-item label="保险公司名称" required><el-input v-model="profileForm.name" /></el-form-item>
              <el-form-item label="联系人"><el-input v-model="profileForm.contact" /></el-form-item>
              <el-form-item label="联系电话"><el-input v-model="profileForm.phone" /></el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="profileSaving" @click="submitProfileEdit">提交变更</el-button>
              </el-form-item>
            </el-form>
            <div v-if="profile?.pending_submitted_at" class="pending-banner">
              有一项变更正在等待平台审核：{{ profile.pending_name || profile.name }} / {{ profile.pending_contact || profile.contact }} / {{ profile.pending_phone || profile.phone }}
            </div>
          </PageCard>
        </el-tab-pane>
      </el-tabs>
    </main>

    <PasswordChangeDialog v-model="passwordDialogVisible" />
  </div>
</template>

<style scoped>
.insurer-portal {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}
.portal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 28px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.portal-brand {
  font-weight: 700;
  font-size: 15px;
}
.portal-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.portal-user {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.portal-body {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px 28px 40px;
}
.pending-banner {
  margin: 0 20px 20px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
</style>
```

- [ ] **Step 7: Register the route**

In `web/src/router/routes.ts`, add after the `agent-portal` route:

```ts
  { path: '/insurer-portal', name: 'insurer-portal', component: () => import('@/views/insurer-portal/InsurerPortalView.vue'), meta: { title: '保司工作台' } },
```

- [ ] **Step 8: Wire `App.vue`, `AppShell.vue`, `LoginView.vue`**

In `web/src/App.vue`, widen `isAuthPage`:

```ts
const isAuthPage = computed(() => route.name === 'login' || route.name === 'certificate' || route.name === 'agent-portal' || route.name === 'insurer-portal')
```

In `web/src/layouts/AppShell.vue`, in `onMounted`, add right after the salesperson redirect block:

```ts
  if (auth.user?.role === 'insurer') {
    router.replace({ name: 'insurer-portal' })
    return
  }
```

In `web/src/views/auth/LoginView.vue`:

Widen the `form.portal` type and the ternary that derives it from `route.query.portal`:

```ts
const form = reactive({
  portal: (route.query.portal === 'enterprise'
    ? 'enterprise'
    : route.query.portal === 'salesperson'
      ? 'salesperson'
      : route.query.portal === 'insurer'
        ? 'insurer'
        : 'admin') as 'admin' | 'enterprise' | 'salesperson' | 'insurer',
  username: isLocal ? 'admin' : '',
  password: isLocal ? 'admin123' : '',
})
```

Add a fourth portal option to the `portals` array:

```ts
  {
    key: 'insurer' as const,
    eyebrow: '04 · 保司端',
    title: '保险公司',
    desc: '岗位核保、保单、结算与理赔审核',
  },
```

Change the post-login redirect in `submit()`:

```ts
    if (auth.user?.role === 'salesperson') {
      router.push('/agent-portal')
    } else if (auth.user?.role === 'insurer') {
      router.push('/insurer-portal')
    } else {
```

Change `.portal-picker` CSS grid from 3 to 4 columns:

```css
.portal-picker {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
  margin-bottom: 28px;
}
```

(Switching to a 2-column grid rather than 4 keeps each card wide enough to read at this card size with four entries; adjust only this rule, no other portal-picker styles change.)

- [ ] **Step 9: Write the profile test**

`tests/insurer_profile_test.py`:

```python
"""Insurer 基本信息编辑: submit writes pending_* only, admin approve/reject."""
import os
import tempfile
from datetime import datetime, timezone

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Insurer, User  # noqa: E402

client = TestClient(app)


def _insurer_token(name="平安保险", username="insurer_profile_test"):
    with SessionLocal() as s:
        insurer = Insurer(name=name)
        s.add(insurer); s.flush()
        s.add(User(username=username, password_hash=pwd.hash("test1234"), name="保司账号", role="insurer", insurer_id=insurer.id))
        s.commit()
        insurer_id = insurer.id
    resp = client.post("/api/auth/login", json={"username": username, "password": "test1234", "portal": "insurer"})
    return resp.json()["access_token"], insurer_id


def test_submit_edit_writes_pending_only():
    token, insurer_id = _insurer_token()
    headers = {"Authorization": f"Bearer {token}"}
    resp = client.patch("/api/insurer-portal/profile", json={"name": "平安保险(新)"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "平安保险"
    assert body["pending_name"] == "平安保险(新)"


def test_admin_approve_commits_change():
    token, insurer_id = _insurer_token(name="太平人寿", username="insurer_profile_test2")
    headers = {"Authorization": f"Bearer {token}"}
    client.patch("/api/insurer-portal/profile", json={"name": "太平人寿(新)"}, headers=headers)
    with SessionLocal() as s:
        if not s.query(User).filter(User.username == "admin_profile_test").first():
            s.add(User(username="admin_profile_test", password_hash=pwd.hash("admin1234"), name="平台", role="admin"))
            s.commit()
    admin_login = client.post("/api/auth/login", json={"username": "admin_profile_test", "password": "admin1234", "portal": "admin"})
    admin_headers = {"Authorization": f"Bearer {admin_login.json()['access_token']}"}
    resp = client.post(f"/api/insurers/{insurer_id}/review-edit", json={"approve": True}, headers=admin_headers)
    assert resp.json()["name"] == "太平人寿(新)"
    assert resp.json()["pending_name"] is None
```

- [ ] **Step 10: Type-check, run tests, build**

```bash
python3 tests/insurer_profile_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_profile_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: both tests pass, no type errors, build succeeds.

- [ ] **Step 11: Commit**

```bash
git add backend/routers/insurer_portal.py backend/app.py web/src/api/insurerPortal.ts \
  web/src/views/insurer-portal/InsurerPortalView.vue web/src/router/routes.ts web/src/App.vue \
  web/src/layouts/AppShell.vue web/src/views/auth/LoginView.vue web/src/api/types.ts \
  web/src/stores/auth.ts tests/insurer_profile_test.py
git commit -m "feat: insurer login portal shell + 基本信息编辑 module"
```

---

## Task 6: 岗位核保 module

**Files:**
- Modify: `backend/routers/positions.py`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Test: `tests/insurer_positions_scope_test.py`

**Interfaces:**
- Consumes: `assert_plan_belongs_to_insurer`, `insurer_plan_ids` (Task 2).
- Produces: insurer-scoped `GET /positions`, insurer-permitted `PATCH /positions/{id}/review`.

- [ ] **Step 1: Widen the review dependency and add the ownership check**

In `backend/routers/positions.py`, add the import:

```python
from ..services import (
    allowed_employer_ids,
    assert_employer_access,
    assert_plan_belongs_to_insurer,
    insurer_plan_ids,
    is_enterprise_owner,
    serialize,
)
```

Change the `review_position` route decorator:

```python
@router.patch("/positions/{item_id}/review", dependencies=[Depends(require_role("admin", "insurer", detail="仅平台或保司端可确定岗位职业类别"))])
def review_position(item_id:int,data:PositionReviewIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(WorkPosition,item_id)
    if not item: raise HTTPException(404,'岗位不存在')
    # 保司只能把岗位分派到自己名下的方案；管理员不受此限制。
    assert_plan_belongs_to_insurer(session,user,data.plan_id)
    videos=session.scalars(select(PositionVideo).where(PositionVideo.position_id==item_id).order_by(PositionVideo.id.desc())).all()
```

(Leave the rest of the function body unchanged — the `assert_plan_belongs_to_insurer` call is inserted right after the existing `if not item:` guard, before the `videos = ...` line.)

- [ ] **Step 2: Scope `GET /positions` for the insurer role**

In `backend/routers/positions.py`, change the `positions()` function's role branch:

```python
@router.get("/positions")
def positions(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(WorkPosition).order_by(WorkPosition.id.desc())
    if user.role == "enterprise" and user.enterprise_id:
        stmt=stmt.where(WorkPosition.enterprise_id==user.enterprise_id)
        allowed=allowed_employer_ids(session,user)
        if allowed is not None: stmt=stmt.where(WorkPosition.actual_employer_id.in_(allowed))
    elif user.role == "insurer":
        # 保司只看得到已经分派到自己名下方案的岗位——未定类（plan_id 为空）
        # 的岗位默认由平台先行审核，不进保司视图。
        plan_ids = insurer_plan_ids(session, user.insurer_id)
        stmt = stmt.where(WorkPosition.plan_id.in_(plan_ids)) if plan_ids else stmt.where(WorkPosition.id.is_(None))
    elif user.role != "admin": raise HTTPException(403,"无权查看岗位")
```

(The rest of the function body is unchanged.)

- [ ] **Step 3: Add the 岗位核保 tab to `InsurerPortalView.vue`**

Add to `web/src/api/insurerPortal.ts`:

```ts
import { client } from './client'
import type { Insurer, WorkPosition } from './types'

export function getInsurerProfile() {
  return client.get<Insurer>('/insurer-portal/profile').then((response) => response.data)
}

export function submitInsurerProfileEdit(data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>('/insurer-portal/profile', data).then((response) => response.data)
}

export function listInsurerPositions() {
  return client.get<WorkPosition[]>('/positions').then((response) => response.data)
}

export function reviewInsurerPosition(id: number, data: { occupation_class?: string; status: 'approved' | 'rejected' | 'supplement'; plan_id?: number | null; review_note?: string }) {
  return client.patch<WorkPosition>(`/positions/${id}/review`, data).then((response) => response.data)
}
```

(This replaces the whole `web/src/api/insurerPortal.ts` file from Task 5 — the import line and existing two functions stay, `WorkPosition` is added to the type import, and the two new functions are appended.)

In `InsurerPortalView.vue`, add imports:

```ts
import { getInsurerProfile, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit } from '@/api/insurerPortal'
import type { Insurer, WorkPosition } from '@/api/types'
```

Add state and a load function:

```ts
const positions = ref<WorkPosition[]>([])
async function loadPositions() {
  positions.value = await listInsurerPositions()
}
```

Call it from `load()`, right after `await loadProfile()`:

```ts
    await loadProfile()
    await loadPositions()
```

Add a review action:

```ts
async function approvePosition(row: WorkPosition) {
  try {
    await reviewInsurerPosition(row.id, { status: 'approved', occupation_class: row.occupation_class, plan_id: row.plan_id })
    ElMessage.success('已核保')
    loadPositions()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
</script>
```

Add the tab pane right after the `基本信息` `</el-tab-pane>`:

```html
        <el-tab-pane label="岗位核保" name="positions">
          <PageCard title="名下岗位" :count="positions.length" hint="仅显示已分派到本保司产品线下的岗位">
            <el-table :data="positions" size="small">
              <el-table-column prop="name" label="岗位名称" min-width="140" />
              <el-table-column prop="actual_employer_name" label="实际用工单位" min-width="160" />
              <el-table-column prop="occupation_class" label="职业类别" width="100" />
              <el-table-column label="状态" width="90">
                <template #default="{ row }"><el-tag size="small" :type="row.status === 'approved' ? 'success' : 'info'">{{ row.status === 'approved' ? '已核保' : row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button v-if="row.status !== 'approved'" link type="primary" size="small" @click="approvePosition(row)">核保通过</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!positions.length" description="暂无名下岗位" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

- [ ] **Step 4: Write the scope test**

`tests/insurer_positions_scope_test.py`:

```python
"""岗位核保范围隔离: insurer A cannot review/see insurer B's positions."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import ActualEmployer, Enterprise, Insurer, InsurancePlan, User, WorkPosition  # noqa: E402

client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="保司A"); insurer_b = Insurer(name="保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="保司A", name="方案A", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="测试企业"); s.add(enterprise); s.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="测试用工单位"); s.add(employer); s.flush()
        position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                   name="岗位B", occupation_class="1-3类", plan_id=plan_b.id, status="approved")
        s.add(position_b); s.flush()
        user_a = User(username="scope_insurer_a", password_hash=pwd.hash("test1234"), name="保司A账号", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return plan_a.id, plan_b.id, position_b.id


def test_insurer_cannot_see_other_insurer_position():
    plan_a_id, plan_b_id, position_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/positions", headers=headers)
    assert resp.status_code == 200
    assert all(row["id"] != position_b_id for row in resp.json())


def test_insurer_cannot_review_position_into_other_insurer_plan():
    plan_a_id, plan_b_id, position_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/positions/{position_b_id}/review",
                        json={"status": "approved", "occupation_class": "1-3类", "plan_id": plan_b_id},
                        headers=headers)
    assert resp.status_code == 403
```

- [ ] **Step 5: Run tests, type-check, build**

```bash
python3 tests/insurer_positions_scope_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_positions_scope_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: both tests pass, no type errors, build succeeds.

- [ ] **Step 6: Commit**

```bash
git add backend/routers/positions.py web/src/views/insurer-portal/InsurerPortalView.vue \
  web/src/api/insurerPortal.ts tests/insurer_positions_scope_test.py
git commit -m "feat: insurer portal 岗位核保 module with insurer_id scoping"
```

---

## Task 7: 上传保单 module

**Files:**
- Modify: `backend/routers/reports.py`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Test: `tests/insurer_policy_upload_scope_test.py`

**Interfaces:**
- Consumes: `assert_plan_belongs_to_insurer` (Task 2).
- Produces: insurer-permitted `POST /policies/{id}/document/upload`, insurer-scoped `GET /policies`.

- [ ] **Step 1: Widen the upload dependency and scope the policy list**

In `backend/routers/reports.py`, add `assert_plan_belongs_to_insurer` to the existing `from ..services import ...` line, and change:

```python
@router.get("/policies")
def policies(user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Policy).order_by(Policy.id.desc())
    if user.role=="enterprise" and user.enterprise_id: stmt=stmt.where(Policy.enterprise_id==user.enterprise_id)
    elif user.role=="insurer":
        plan_ids={x.id for x in session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id==user.insurer_id))}
        stmt=stmt.where(Policy.plan_id.in_(plan_ids)) if plan_ids else stmt.where(Policy.id.is_(None))
    return [_policy_with_document(x,session,user) for x in session.scalars(stmt)]

@router.post("/policies/{item_id}/document/upload", dependencies=[Depends(require_role("admin", "insurer", detail="仅平台或保司端可导入保单文件"))])
async def upload_policy_document(item_id:int,file:UploadFile=File(...),user:User=Depends(current_user),session:Session=Depends(db)):
    policy=session.get(Policy,item_id)
    if not policy: raise HTTPException(404,'保单不存在')
    assert_plan_belongs_to_insurer(session,user,policy.plan_id)
    suffix=Path(file.filename or '').suffix.lower()
```

(Everything after the `suffix=` line in `upload_policy_document` is unchanged.)

- [ ] **Step 2: Add the 上传保单 tab**

Add to `web/src/api/insurerPortal.ts`:

```ts
import type { Insurer, Policy, WorkPosition } from './types'

export function listInsurerPolicies() {
  return client.get<Policy[]>('/policies').then((response) => response.data)
}

export function uploadInsurerPolicyDocument(policyId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return client.post<Policy>(`/policies/${policyId}/document/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((response) => response.data)
}
```

(`Policy` join the existing type import list; the two functions are appended after `reviewInsurerPosition`.)

In `InsurerPortalView.vue`, add to the imports:

```ts
import { getInsurerProfile, listInsurerPolicies, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { Insurer, Policy, WorkPosition } from '@/api/types'
```

Add state, load call, and an upload handler:

```ts
const policies = ref<Policy[]>([])
async function loadPolicies() {
  policies.value = await listInsurerPolicies()
}

async function handlePolicyUpload(policyId: number, file: File) {
  try {
    await uploadInsurerPolicyDocument(policyId, file)
    ElMessage.success('保单文件已上传')
    loadPolicies()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

Call `loadPolicies()` from `load()`, right after `await loadPositions()`:

```ts
    await loadPositions()
    await loadPolicies()
```

Add the tab pane after the `岗位核保` `</el-tab-pane>`:

```html
        <el-tab-pane label="上传保单" name="policies">
          <PageCard title="名下保单" :count="policies.length">
            <el-table :data="policies" size="small">
              <el-table-column prop="policy_no" label="保单号" min-width="160" />
              <el-table-column label="保费" width="100"><template #default="{ row }">{{ row.premium }}</template></el-table-column>
              <el-table-column label="保单文件" min-width="160">
                <template #default="{ row }">
                  <a v-if="row.document_download_url" :href="row.document_download_url" target="_blank">{{ row.document_name || '查看文件' }}</a>
                  <span v-else class="muted">未上传</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="140">
                <template #default="{ row }">
                  <el-upload :show-file-list="false" :auto-upload="false" accept=".pdf,.jpg,.jpeg,.png"
                             @change="(f: { raw: File }) => handlePolicyUpload(row.id, f.raw)">
                    <el-button link type="primary" size="small">{{ row.document_download_url ? '重新上传' : '上传' }}</el-button>
                  </el-upload>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!policies.length" description="暂无名下保单" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

- [ ] **Step 3: Write the scope test**

`tests/insurer_policy_upload_scope_test.py`:

```python
"""保单上传范围隔离: insurer cannot see or upload to another insurer's policy."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Enterprise, Insurer, InsurancePlan, Policy, User  # noqa: E402

client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="保单保司A"); insurer_b = Insurer(name="保单保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="保单保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise = Enterprise(name="保单测试企业"); s.add(enterprise); s.flush()
        policy_b = Policy(policy_no="POL-SCOPE-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(policy_b); s.flush()
        user_a = User(username="scope_policy_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return policy_b.id


def test_insurer_cannot_see_other_insurer_policy():
    policy_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_policy_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/policies", headers=headers)
    assert resp.status_code == 200
    assert all(row["id"] != policy_b_id for row in resp.json())


def test_insurer_cannot_upload_to_other_insurer_policy():
    policy_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "scope_policy_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.post(f"/api/policies/{policy_b_id}/document/upload", headers=headers,
                       files={"file": ("test.pdf", b"%PDF-1.4 fake", "application/pdf")})
    assert resp.status_code == 403
```

- [ ] **Step 4: Run tests, type-check, build**

```bash
python3 tests/insurer_policy_upload_scope_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_policy_upload_scope_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: both tests pass, no type errors, build succeeds.

- [ ] **Step 5: Commit**

```bash
git add backend/routers/reports.py web/src/views/insurer-portal/InsurerPortalView.vue \
  web/src/api/insurerPortal.ts tests/insurer_policy_upload_scope_test.py
git commit -m "feat: insurer portal 上传保单 module with insurer_id scoping"
```

---

## Task 8: 财务管理 module (settlement)

**Files:**
- Create: `backend/services/insurer_settlement.py`
- Modify: `backend/services/__init__.py`
- Modify: `backend/routers/insurer_portal.py`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Test: `tests/insurer_settlement_test.py`

**Interfaces:**
- Consumes: `insurer_plan_ids` (Task 2), `strip_internal_pricing` with the `insurer` blacklist (Task 2).
- Produces: `insurer_settlement_summary(session, insurer_id) -> dict` (service), `GET /api/insurer-portal/settlement`.

- [ ] **Step 1: Write the settlement service**

`backend/services/insurer_settlement.py`:

```python
"""Insurer-facing settlement view (2026-07-24 design §财务管理).

Aggregates by enterprise, over the insurer's own plans only. This is a
premium-and-arrears view, not a commission ledger — internal cost/profit
fields never enter this function's output (strip_internal_pricing handles
the per-row pricing_snapshot fields that do get included).
"""
from sqlalchemy import select
from sqlalchemy.orm import Session

from ..models import Enterprise, InsurancePlan, Policy
from .pricing import pricing_snapshot, strip_internal_pricing
from .serialization import amount


def insurer_settlement_summary(session: Session, insurer_id: int, user) -> dict:
    plan_ids = set(session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id == insurer_id)))
    if not plan_ids:
        return {"insurer_id": insurer_id, "total_active_premium": 0.0, "rows": []}

    rows = []
    total_active_premium = 0.0
    policies = session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids)).order_by(Policy.id.desc()))
    for policy in policies:
        plan = session.get(InsurancePlan, policy.plan_id)
        enterprise = session.get(Enterprise, policy.enterprise_id)
        snapshot = pricing_snapshot(plan) if plan else {}
        row = strip_internal_pricing({
            "policy_id": policy.id,
            "policy_no": policy.policy_no,
            "enterprise_name": enterprise.name if enterprise else "",
            "plan_name": plan.name if plan else "",
            "status": policy.status,
            "premium": amount(policy.premium),
            **snapshot,
        }, user)
        rows.append(row)
        if policy.status == "active":
            total_active_premium += float(policy.premium or 0)

    return {"insurer_id": insurer_id, "total_active_premium": amount(total_active_premium), "rows": rows}
```

- [ ] **Step 2: Export from `services/__init__.py`**

Add `from .insurer_settlement import insurer_settlement_summary` and `"insurer_settlement_summary",` to `__all__`.

- [ ] **Step 3: Add the settlement endpoint**

In `backend/routers/insurer_portal.py`, add the import:

```python
from ..services import insurer_settlement_summary
```

Append:

```python
@router.get("/settlement", dependencies=[Depends(_INSURER)])
def settlement(user: User = Depends(current_user), session: Session = Depends(db)):
    return insurer_settlement_summary(session, user.insurer_id, user)
```

- [ ] **Step 4: Add the 财务管理 tab**

Add to `web/src/api/insurerPortal.ts`:

```ts
export interface InsurerSettlementRow {
  policy_id: number
  policy_no: string
  enterprise_name: string
  plan_name: string
  status: string
  premium: number
  insurance_base_price?: number
  policy_floor_price?: number
  insurer_settlement_price?: number
  minimum_sale_price?: number
  sale_price?: number
}

export interface InsurerSettlement {
  insurer_id: number
  total_active_premium: number
  rows: InsurerSettlementRow[]
}

export function getInsurerSettlement() {
  return client.get<InsurerSettlement>('/insurer-portal/settlement').then((response) => response.data)
}
```

In `InsurerPortalView.vue`, add to imports:

```ts
import { getInsurerProfile, getInsurerSettlement, listInsurerPolicies, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { Insurer, InsurerSettlement, Policy, WorkPosition } from '@/api/types'
```

Add state and load call:

```ts
const settlement = ref<InsurerSettlement | null>(null)
async function loadSettlement() {
  settlement.value = await getInsurerSettlement()
}
```

Call it from `load()`, right after `await loadPolicies()`:

```ts
    await loadPolicies()
    await loadSettlement()
```

Add the tab pane after the `上传保单` `</el-tab-pane>`:

```html
        <el-tab-pane label="财务管理" name="settlement">
          <PageCard title="保费结算总览" hint="仅显示保费与结算价，平台内部利润/返佣数据不对保司开放">
            <div class="stat-grid">
              <div class="stat-tile">
                <div class="stat-label">在保保费合计</div>
                <div class="stat-value">{{ settlement?.total_active_premium ?? '—' }}</div>
              </div>
            </div>
          </PageCard>
          <PageCard title="保单结算明细" :count="settlement?.rows.length || 0">
            <el-table :data="settlement?.rows || []" size="small">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="plan_name" label="产品方案" min-width="140" />
              <el-table-column prop="policy_no" label="保单号" min-width="140" />
              <el-table-column label="保费" width="100"><template #default="{ row }">{{ row.premium }}</template></el-table-column>
              <el-table-column label="结算价" width="100"><template #default="{ row }">{{ row.policy_floor_price ?? '—' }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="!settlement?.rows.length" description="暂无结算数据" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

Add to `<style scoped>`:

```css
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  padding: 0 20px 20px;
}
.stat-tile {
  padding: 16px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  margin-top: 6px;
}
```

- [ ] **Step 5: Write the isolation test**

`tests/insurer_settlement_test.py`:

```python
"""财务管理: insurer sees own-insurer settlement rows only, no profit fields."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Enterprise, Insurer, InsurancePlan, Policy, User  # noqa: E402

client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="结算保司A"); insurer_b = Insurer(name="结算保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="结算保司A", name="方案A", price=100, commission_rate=0.2, profit_amount=10, insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="结算保司B", name="方案B", price=200, insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="结算测试企业"); s.add(enterprise); s.flush()
        s.add(Policy(policy_no="POL-SETTLE-A", enterprise_id=enterprise.id, plan_id=plan_a.id, premium=100, status="active"))
        s.add(Policy(policy_no="POL-SETTLE-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=200, status="active"))
        s.flush()
        user_a = User(username="settle_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()


def test_insurer_settlement_scoped_and_hides_profit():
    _setup()
    login = client.post("/api/auth/login", json={"username": "settle_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/insurer-portal/settlement", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert all(row["policy_no"] != "POL-SETTLE-B" for row in body["rows"])
    assert any(row["policy_no"] == "POL-SETTLE-A" for row in body["rows"])
    for row in body["rows"]:
        assert "profit_amount" not in row
        assert "agent_commission_amount" not in row
```

- [ ] **Step 6: Run test, type-check, build**

```bash
python3 tests/insurer_settlement_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_settlement_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: test passes, no type errors, build succeeds.

- [ ] **Step 7: Commit**

```bash
git add backend/services/insurer_settlement.py backend/services/__init__.py backend/routers/insurer_portal.py \
  web/src/views/insurer-portal/InsurerPortalView.vue web/src/api/insurerPortal.ts tests/insurer_settlement_test.py
git commit -m "feat: insurer portal 财务管理 module with profit-field blacklist"
```

---

## Task 9: 发票管理 module

**Files:**
- Modify: `backend/routers/invoices.py`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Test: `tests/insurer_invoices_scope_test.py`

**Interfaces:**
- Consumes: `insurer_plan_ids` (Task 2).
- Produces: insurer-scoped `GET /invoices`.

- [ ] **Step 1: Scope invoice listing for the insurer role**

In `backend/routers/invoices.py`, add `InsurancePlan, Policy` to the `..models` import (already imports `Enterprise, InsurancePlan, Invoice, Policy, User` — confirm and reuse), and change:

```python
@router.get("/invoices")
def invoices(user:User=Depends(current_user),session:Session=Depends(db)):
    stmt=select(Invoice).order_by(Invoice.id.desc())
    if user.role=='enterprise' and user.enterprise_id: stmt=stmt.where(Invoice.enterprise_id==user.enterprise_id)
    elif user.role=='insurer':
        # Invoice 没有直接的 plan_id，通过该单位在本保司名下有保单来判定可见性——
        # 与"财务管理"结算范围保持同一颗粒度（按投保单位，不按单张发票挂钩具体保单）。
        plan_ids={x.id for x in session.scalars(select(InsurancePlan.id).where(InsurancePlan.insurer_id==user.insurer_id))}
        enterprise_ids={x.enterprise_id for x in session.scalars(select(Policy).where(Policy.plan_id.in_(plan_ids)))} if plan_ids else set()
        stmt=stmt.where(Invoice.enterprise_id.in_(enterprise_ids)) if enterprise_ids else stmt.where(Invoice.id.is_(None))
    elif user.role!='admin': raise HTTPException(403,'无权查看发票')
    result=[]
    for item in session.scalars(stmt):
        enterprise=session.get(Enterprise,item.enterprise_id)
        result.append({**serialize(item),'enterprise_name':enterprise.name if enterprise else ''})
    return result
```

- [ ] **Step 2: Add the 发票管理 tab**

Add to `web/src/api/insurerPortal.ts`:

```ts
import type { Insurer, Invoice, InsurerSettlement, Policy, WorkPosition } from './types'

export function listInsurerInvoices() {
  return client.get<Invoice[]>('/invoices').then((response) => response.data)
}
```

In `InsurerPortalView.vue`, add to imports:

```ts
import { getInsurerProfile, getInsurerSettlement, listInsurerInvoices, listInsurerPolicies, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { Insurer, Invoice, InsurerSettlement, Policy, WorkPosition } from '@/api/types'
```

Add state and load call:

```ts
const invoices = ref<Invoice[]>([])
async function loadInvoices() {
  invoices.value = await listInsurerInvoices()
}
```

Call it from `load()`, right after `await loadSettlement()`:

```ts
    await loadSettlement()
    await loadInvoices()
```

Add the tab pane after `财务管理`:

```html
        <el-tab-pane label="发票管理" name="invoices">
          <PageCard title="名下投保单位发票申请" :count="invoices.length">
            <el-table :data="invoices" size="small">
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="account" label="费用类型" width="100">
                <template #default="{ row }">{{ row.account === 'premium' ? '保费' : '使用费' }}</template>
              </el-table-column>
              <el-table-column label="金额" width="100"><template #default="{ row }">{{ row.amount }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="90" />
            </el-table>
            <el-empty v-if="!invoices.length" description="暂无发票申请" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

- [ ] **Step 3: Write the scope test**

`tests/insurer_invoices_scope_test.py`:

```python
"""发票管理范围隔离: insurer only sees invoices for enterprises with a policy under its own plans."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Enterprise, Insurer, InsurancePlan, Invoice, Policy, User  # noqa: E402

client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="发票保司A"); insurer_b = Insurer(name="发票保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="发票保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise_b = Enterprise(name="发票测试企业B")
        s.add(enterprise_b); s.flush()
        s.add(Policy(policy_no="POL-INV-B", enterprise_id=enterprise_b.id, plan_id=plan_b.id, premium=100))
        s.add(Invoice(enterprise_id=enterprise_b.id, account="premium", amount=100))
        s.flush()
        user_a = User(username="invoice_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()


def test_insurer_sees_no_invoices_for_other_insurers_enterprise():
    _setup()
    login = client.post("/api/auth/login", json={"username": "invoice_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.get("/api/invoices", headers=headers)
    assert resp.status_code == 200
    assert resp.json() == []
```

- [ ] **Step 4: Run test, type-check, build**

```bash
python3 tests/insurer_invoices_scope_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_invoices_scope_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: test passes, no type errors, build succeeds.

- [ ] **Step 5: Commit**

```bash
git add backend/routers/invoices.py web/src/views/insurer-portal/InsurerPortalView.vue \
  web/src/api/insurerPortal.ts tests/insurer_invoices_scope_test.py
git commit -m "feat: insurer portal 发票管理 module with insurer_id scoping"
```

---

## Task 10: 员工参停保异常标注 module

**Files:**
- Modify: `backend/routers/insured.py`
- Modify: `backend/schemas/insured.py`
- Modify: `web/src/views/insured/WorkersView.vue`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Modify: `web/src/api/types.ts`
- Test: `tests/insurer_flag_test.py`

**Interfaces:**
- Consumes: `InsuredPerson.insurer_flag_reason` etc. (Task 1).
- Produces: `PATCH /insured/{id}/insurer-flag`, `GET /insurer-portal/insured`.

- [ ] **Step 1: Add the flag schema**

In `backend/schemas/insured.py`, add:

```python
class InsurerFlagIn(BaseModel): reason: str = ""
```

Register in `backend/schemas/__init__.py`: add `InsurerFlagIn` to the existing `from .insured import PersonIn, PersonUpdate, BulkPersonRow, BulkPersonIn` line and to `__all__`.

- [ ] **Step 2: Add the flag endpoint to `insured.py`**

In `backend/routers/insured.py`, add the import:

```python
from ..schemas import BulkPersonIn, InsurerFlagIn, PersonIn, PersonUpdate
```

Append after `insured_status`:

```python
@router.patch("/insured/{item_id}/insurer-flag", dependencies=[Depends(require_role("insurer", detail="仅保司账号可标注参停保异常"))])
def flag_insured_person(item_id:int,data:InsurerFlagIn,user:User=Depends(current_user),session:Session=Depends(db)):
    """保司只能标注异常原因，不能触碰参保状态本身——见 2026-07-24 设计文档
    "范围边界"：不是参停保的责任方，只是发现问题后标注、推动企业/平台处理。"""
    item=session.get(InsuredPerson,item_id)
    if not item: raise HTTPException(404,'参保员工不存在')
    position=session.get(WorkPosition,item.position_id) if item.position_id else None
    plan=session.get(InsurancePlan,position.plan_id) if position and position.plan_id else None
    if not plan or plan.insurer_id!=user.insurer_id: raise HTTPException(403,'无权标注其他保险公司名下的参保员工')
    reason=data.reason.strip()
    item.insurer_flag_reason=reason
    item.insurer_flagged_at=datetime.now() if reason else None
    item.insurer_flagged_by=user.id if reason else None
    session.commit();audit(session,user,'update','insured_person_flag',str(item.id),reason or 'cleared')
    return _person_payload(session,item)
```

Add `from datetime import datetime, timezone` to the existing `from datetime import datetime` import in `insured.py` if not already present (it already imports `datetime`, no change needed beyond what's already there — just confirm `datetime.now()` usage matches the rest of the file's style, which it does).

Add `require_role` to the existing `from ..core.rbac import` import (currently there's no `rbac` import in `insured.py` — add: `from ..core.rbac import require_role` as a new import line right after `from ..core.security import current_user`).

- [ ] **Step 3: Add the flag fields to `_person_payload` and the `GET /insured` listing**

`serialize(item)` already returns every model column (`services/serialization.py`'s `serialize()` iterates `obj.__table__.columns`), so `insurer_flag_reason`/`insurer_flagged_at`/`insurer_flagged_by` are already present in every `_person_payload()` response and every `GET /insured` row with no further code change — confirm this by reading `serialize()`'s implementation (Task 1 Step 6 added these as real mapped columns, so they're `obj.__table__.columns` members automatically).

- [ ] **Step 4: Add the `GET /insurer-portal/insured` read-only listing**

In `backend/routers/insurer_portal.py`, add imports:

```python
from ..models import Insurer, InsurancePlan, InsuredPerson, User, WorkPosition
from ..services import insurer_settlement_summary, insurer_plan_ids, serialize
from sqlalchemy import select
```

(Merge with the existing import lines rather than duplicating — the file already imports `Insurer, User` from `..models` and `db` from `..core.db`; extend those lines.)

Append:

```python
@router.get("/insured", dependencies=[Depends(_INSURER)])
def insured_for_review(user: User = Depends(current_user), session: Session = Depends(db)):
    plan_ids = insurer_plan_ids(session, user.insurer_id)
    if not plan_ids:
        return []
    stmt = select(InsuredPerson).join(WorkPosition, InsuredPerson.position_id == WorkPosition.id).where(
        WorkPosition.plan_id.in_(plan_ids)).order_by(InsuredPerson.id.desc())
    return [serialize(x) for x in session.scalars(stmt)]
```

- [ ] **Step 5: Display the flag on the enterprise-facing worker list**

In `web/src/views/insured/WorkersView.vue`, add a column right after the existing 状态 column in the main `<el-table>`:

```html
              <el-table-column label="保司标注" width="140">
                <template #default="{ row }">
                  <el-tag v-if="row.insurer_flag_reason" type="danger" size="small">{{ row.insurer_flag_reason }}</el-tag>
                </template>
              </el-table-column>
```

(Read the file first to find the exact existing 状态 `<el-table-column>` block and insert this one immediately after it, matching the surrounding table's column-width conventions.)

- [ ] **Step 6: Add the 员工参停保异常标注 tab**

Add to `web/src/api/types.ts`, in the `InsuredPerson` interface (find it and add):

```ts
  insurer_flag_reason: string
  insurer_flagged_at: string | null
  insurer_flagged_by: number | null
```

Add to `web/src/api/insurerPortal.ts`:

```ts
import type { Insurer, Invoice, InsuredPerson, InsurerSettlement, Policy, WorkPosition } from './types'

export function listInsurerInsured() {
  return client.get<InsuredPerson[]>('/insurer-portal/insured').then((response) => response.data)
}

export function flagInsuredPerson(id: number, reason: string) {
  return client.patch<InsuredPerson>(`/insured/${id}/insurer-flag`, { reason }).then((response) => response.data)
}
```

In `InsurerPortalView.vue`, add to imports:

```ts
import { flagInsuredPerson, getInsurerProfile, getInsurerSettlement, listInsurerInsured, listInsurerInvoices, listInsurerPolicies, listInsurerPositions, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { Insurer, Invoice, InsuredPerson, InsurerSettlement, Policy, WorkPosition } from '@/api/types'
```

Add state, load call, dialog logic:

```ts
const insuredList = ref<InsuredPerson[]>([])
async function loadInsured() {
  insuredList.value = await listInsurerInsured()
}

const flagDialogVisible = ref(false)
const flagTarget = ref<InsuredPerson | null>(null)
const flagReason = ref('')
function openFlagDialog(row: InsuredPerson) {
  flagTarget.value = row
  flagReason.value = row.insurer_flag_reason || ''
  flagDialogVisible.value = true
}
async function submitFlag() {
  if (!flagTarget.value) return
  try {
    await flagInsuredPerson(flagTarget.value.id, flagReason.value)
    ElMessage.success(flagReason.value ? '已标注' : '已取消标注')
    flagDialogVisible.value = false
    loadInsured()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

Call `loadInsured()` from `load()`, right after `await loadInvoices()`:

```ts
    await loadInvoices()
    await loadInsured()
```

Add the tab pane after `发票管理`:

```html
        <el-tab-pane label="员工参停保异常标注" name="insured">
          <PageCard title="名下参保员工" :count="insuredList.length" hint="只能标注异常原因，不能直接修改参保状态">
            <el-table :data="insuredList" size="small">
              <el-table-column prop="name" label="姓名" width="100" />
              <el-table-column prop="id_number" label="身份证号" min-width="180" />
              <el-table-column prop="status" label="状态" width="90" />
              <el-table-column label="异常标注" min-width="160">
                <template #default="{ row }">
                  <el-tag v-if="row.insurer_flag_reason" type="danger" size="small">{{ row.insurer_flag_reason }}</el-tag>
                  <span v-else class="muted">无</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="100">
                <template #default="{ row }"><el-button link type="primary" size="small" @click="openFlagDialog(row)">标注</el-button></template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!insuredList.length" description="暂无名下参保员工" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

Add the dialog right before the closing `<PasswordChangeDialog v-model="passwordDialogVisible" />`:

```html
    <el-dialog v-model="flagDialogVisible" title="标注参停保异常" width="420px">
      <el-input v-model="flagReason" type="textarea" :rows="3" placeholder="留空并提交表示取消标注" />
      <template #footer>
        <el-button @click="flagDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFlag">提交</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 7: Write the flag test**

`tests/insurer_flag_test.py`:

```python
"""员工参停保异常标注: insurer can flag/clear own-scope people, not other-insurer people, and never touches status."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import ActualEmployer, Enterprise, Insurer, InsurancePlan, InsuredPerson, User, WorkPosition  # noqa: E402

client = TestClient(app)


def _setup():
    with SessionLocal() as s:
        insurer_a = Insurer(name="标注保司A"); insurer_b = Insurer(name="标注保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="标注保司A", name="方案A", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="标注保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="标注测试企业"); s.add(enterprise); s.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="标注用工单位"); s.add(employer); s.flush()
        position_a = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                   name="岗位A", occupation_class="1-3类", plan_id=plan_a.id, status="approved")
        position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id, actual_employer=employer.name,
                                   name="岗位B", occupation_class="1-3类", plan_id=plan_b.id, status="approved")
        s.add(position_a); s.add(position_b); s.flush()
        person_a = InsuredPerson(enterprise_id=enterprise.id, name="张三", id_number="340123199001011234",
                                  position_id=position_a.id, status="active")
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="李四", id_number="340123199001011235",
                                  position_id=position_b.id, status="active")
        s.add(person_a); s.add(person_b); s.flush()
        user_a = User(username="flag_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return person_a.id, person_b.id


def test_insurer_can_flag_own_scope_person():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": "保单信息与员工不符"}, headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body["insurer_flag_reason"] == "保单信息与员工不符"
    assert body["status"] == "active"


def test_insurer_cannot_flag_other_insurer_person():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    resp = client.patch(f"/api/insured/{person_b_id}/insurer-flag", json={"reason": "越权测试"}, headers=headers)
    assert resp.status_code == 403


def test_clear_flag_by_empty_reason():
    person_a_id, person_b_id = _setup()
    login = client.post("/api/auth/login", json={"username": "flag_insurer_a", "password": "test1234", "portal": "insurer"})
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}
    client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": "有问题"}, headers=headers)
    resp = client.patch(f"/api/insured/{person_a_id}/insurer-flag", json={"reason": ""}, headers=headers)
    assert resp.status_code == 200
    assert resp.json()["insurer_flag_reason"] == ""
```

- [ ] **Step 8: Run tests, type-check, build**

```bash
python3 tests/insurer_flag_test.py 2>&1 | tail -10 || python3 -m pytest tests/insurer_flag_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: all 3 tests pass, no type errors, build succeeds.

- [ ] **Step 9: Commit**

```bash
git add backend/routers/insured.py backend/schemas/insured.py backend/schemas/__init__.py \
  backend/routers/insurer_portal.py web/src/views/insured/WorkersView.vue \
  web/src/views/insurer-portal/InsurerPortalView.vue web/src/api/insurerPortal.ts web/src/api/types.ts \
  tests/insurer_flag_test.py
git commit -m "feat: insurer portal 员工参停保异常标注 module"
```

---

## Task 11: 理赔管理 module

**Files:**
- Modify: `backend/routers/claims.py`
- Modify: `backend/services/claims.py`
- Modify: `web/src/views/insurer-portal/InsurerPortalView.vue`
- Modify: `web/src/api/insurerPortal.ts`
- Test: `tests/insurer_claims_scope_test.py`

**Interfaces:**
- Consumes: `claim_insurer_id` (Task 2).
- Produces: insurer-permitted `PATCH /claims/{id}/status` (restricted to `insurer_review` → `approved`/`rejected`/`supplement`), insurer read access to claims at `insurer_review` or later, insurer-scoped `GET /claims`.

- [ ] **Step 1: Widen `claim_access` for the insurer role**

In `backend/services/claims.py`, add the import:

```python
from .employer_scopes import assert_employer_access, is_enterprise_owner
```

(already present) — add a new import line right after it:

```python
from .insurer_scope import claim_insurer_id
```

Change `claim_access`:

```python
_INSURER_VISIBLE_CLAIM_STATUSES = {'insurer_review', 'supplement', 'approved', 'paid', 'rejected', 'closed'}


def claim_access(item:Claim,user:User,session:Session):
    if user.role=='insurer':
        # 保司只看得到已经流转到 insurer_review 或之后节点、且挂在自己
        # insurer_id 名下的案件——更早期节点（reported/collecting/submitted）
        # 不需要保司介入，不开放查看，减少不必要的信息暴露（见设计文档
        # "理赔管理"）。
        if item.status not in _INSURER_VISIBLE_CLAIM_STATUSES or claim_insurer_id(item,session)!=user.insurer_id:
            raise HTTPException(403,'无权访问该理赔案件')
        return
    if user.role=='enterprise' and user.enterprise_id!=item.enterprise_id: raise HTTPException(403,'无权访问该理赔案件')
    if user.role not in {'admin','enterprise'}: raise HTTPException(403,'无权访问理赔案件')
    person=session.get(InsuredPerson,item.person_id)
    if not person: raise HTTPException(404,'理赔员工不存在')
    person_claim_access(person,user,session)
```

- [ ] **Step 2: Widen the `claim_status` permission check in `claims.py`**

In `backend/routers/claims.py`, add the import:

```python
from ..services.insurer_scope import claim_insurer_id
```

Change `claim_status`:

```python
@router.patch("/claims/{item_id}/status")
def claim_status(item_id:int,data:ClaimStatusIn,user:User=Depends(current_user),session:Session=Depends(db)):
    item=session.get(Claim,item_id)
    if not item: raise HTTPException(404,"理赔案件不存在")
    claim_access(item,user,session)
    if data.status not in CLAIM_TRANSITIONS.get(item.status,set()): raise HTTPException(409,f'案件不能从 {item.status} 变更为 {data.status}')
    if user.role=='enterprise' and data.status!='submitted': raise HTTPException(403,'该节点需由平台理赔人员处理')
    if user.role=='insurer':
        if item.status!='insurer_review': raise HTTPException(403,'保司只能处理保司审核中的案件')
        if data.status not in {'approved','rejected','supplement'}: raise HTTPException(403,'保司只能核赔通过、拒赔或打回补件')
        if claim_insurer_id(item,session)!=user.insurer_id: raise HTTPException(403,'无权操作其他保险公司的理赔案件')
    if data.status=='submitted':
```

(The rest of the function body — from `uploaded={x.doc_type ...}` onward — is unchanged.)

- [ ] **Step 3: Scope `GET /claims` for the insurer role**

In `backend/routers/claims.py`, change the `claims()` function:

```python
@router.get("/claims")
def claims(q:str="",status_filter:Optional[str]=Query(default=None,alias='status'),risk:Optional[str]=None,enterprise_id:Optional[int]=None,user: User = Depends(current_user), session: Session = Depends(db)):
    stmt=select(Claim).order_by(Claim.id.desc())
    if user.role=='enterprise' and user.enterprise_id:
        stmt=stmt.where(Claim.enterprise_id==user.enterprise_id)
        allowed=allowed_employer_ids(session,user)
        if allowed is not None:
            from ..models import WorkPosition
            stmt=stmt.join(InsuredPerson,Claim.person_id==InsuredPerson.id).join(WorkPosition,InsuredPerson.position_id==WorkPosition.id).where(WorkPosition.actual_employer_id.in_(allowed))
    elif enterprise_id: stmt=stmt.where(Claim.enterprise_id==enterprise_id)
    if user.role not in {'admin','enterprise','insurer'}: raise HTTPException(403,'无权查看理赔案件')
    if status_filter: stmt=stmt.where(Claim.status==status_filter)
    rows=[claim_payload(item,session) for item in session.scalars(stmt)]
    if user.role=='insurer':
        rows=[row for row in rows if row['id'] in {
            item.id for item in session.scalars(select(Claim)) if item.status in _INSURER_VISIBLE_CLAIM_STATUSES and claim_insurer_id(item,session)==user.insurer_id
        }]
    if q:
        needle=q.lower();rows=[item for item in rows if needle in f"{item['claim_no']}{item['person_name']}{item['enterprise_name']}{item['actual_employer_name']}".lower()]
    if risk: rows=[item for item in rows if item['calculated_risk']==risk]
    return rows
```

Add `_INSURER_VISIBLE_CLAIM_STATUSES` to the import from `..services.claims`:

```python
from ..services.claims import (
    CLAIM_REQUIRED_DOCS, CLAIM_REQUIRED_TYPES, CLAIM_TRANSITIONS, _INSURER_VISIBLE_CLAIM_STATUSES,
    claim_access, claim_payload, person_claim_access, prepare_claim_upload,
)
```

(This filter is intentionally re-deriving membership via a second query rather than filtering the SQL `stmt` directly, because `_INSURER_VISIBLE_CLAIM_STATUSES` and `claim_insurer_id`'s policy-chain resolution aren't expressible as a single join without duplicating `claim_payload`'s own policy-fallback logic — correctness over cleverness for a case that only ever returns a small, already-narrow-by-status result set.)

- [ ] **Step 4: Add the 理赔管理 tab**

Add to `web/src/api/insurerPortal.ts`:

```ts
import type { Claim, Insurer, Invoice, InsuredPerson, InsurerSettlement, Policy, WorkPosition } from './types'

export function listInsurerClaims() {
  return client.get<Claim[]>('/claims').then((response) => response.data)
}

export function reviewInsurerClaim(id: number, data: { status: 'approved' | 'rejected' | 'supplement'; approved_amount?: number; rejection_reason?: string; note?: string }) {
  return client.patch<Claim>(`/claims/${id}/status`, data).then((response) => response.data)
}
```

In `InsurerPortalView.vue`, add to imports:

```ts
import { flagInsuredPerson, getInsurerProfile, getInsurerSettlement, listInsurerClaims, listInsurerInsured, listInsurerInvoices, listInsurerPolicies, listInsurerPositions, reviewInsurerClaim, reviewInsurerPosition, submitInsurerProfileEdit, uploadInsurerPolicyDocument } from '@/api/insurerPortal'
import type { Claim, Insurer, Invoice, InsuredPerson, InsurerSettlement, Policy, WorkPosition } from '@/api/types'
```

Add state, load call, and review dialog:

```ts
const claims = ref<Claim[]>([])
async function loadClaims() {
  claims.value = await listInsurerClaims()
}

const claimDialogVisible = ref(false)
const claimTarget = ref<Claim | null>(null)
const claimForm = reactive({ status: 'approved' as 'approved' | 'rejected' | 'supplement', approved_amount: 0, rejection_reason: '', note: '' })
function openClaimDialog(row: Claim) {
  claimTarget.value = row
  Object.assign(claimForm, { status: 'approved', approved_amount: 0, rejection_reason: '', note: '' })
  claimDialogVisible.value = true
}
async function submitClaimReview() {
  if (!claimTarget.value) return
  if (claimForm.status === 'approved' && claimForm.approved_amount <= 0) { ElMessage.error('核赔通过需登记核赔金额'); return }
  if (claimForm.status === 'rejected' && !claimForm.rejection_reason.trim()) { ElMessage.error('拒赔需填写原因'); return }
  try {
    await reviewInsurerClaim(claimTarget.value.id, claimForm)
    ElMessage.success('已提交')
    claimDialogVisible.value = false
    loadClaims()
  } catch (e) {
    ElMessage.error((e as Error).message)
  }
}
```

Call `loadClaims()` from `load()`, right after `await loadInsured()`:

```ts
    await loadInsured()
    await loadClaims()
```

Add the tab pane after `员工参停保异常标注`:

```html
        <el-tab-pane label="理赔管理" name="claims">
          <PageCard title="名下理赔案件" :count="claims.length" hint="只展示已流转到保司审核中或之后节点的案件">
            <el-table :data="claims" size="small">
              <el-table-column prop="claim_no" label="案件号" min-width="160" />
              <el-table-column prop="person_name" label="被保险人" width="100" />
              <el-table-column prop="enterprise_name" label="投保单位" min-width="140" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column label="操作" width="100">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'insurer_review'" link type="primary" size="small" @click="openClaimDialog(row)">审核</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!claims.length" description="暂无名下理赔案件" :image-size="60" />
          </PageCard>
        </el-tab-pane>
```

Add the review dialog right after the flag dialog:

```html
    <el-dialog v-model="claimDialogVisible" title="理赔审核" width="480px">
      <el-form :model="claimForm" label-width="100px">
        <el-form-item label="审核结论">
          <el-radio-group v-model="claimForm.status">
            <el-radio-button value="approved">核赔通过</el-radio-button>
            <el-radio-button value="rejected">拒赔</el-radio-button>
            <el-radio-button value="supplement">打回补件</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="claimForm.status === 'approved'" label="核赔金额" required>
          <el-input-number v-model="claimForm.approved_amount" :min="0" :step="100" />
        </el-form-item>
        <el-form-item v-if="claimForm.status === 'rejected'" label="拒赔原因" required>
          <el-input v-model="claimForm.rejection_reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="claimForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitClaimReview">提交</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 5: Write the isolation test**

`tests/insurer_claims_scope_test.py`:

```python
"""理赔管理: insurer can only act on own-insurer claims already at insurer_review,
cannot see earlier-stage claims, cannot skip straight to paid/closed."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import Claim, Enterprise, Insurer, InsurancePlan, InsuredPerson, Policy, User  # noqa: E402

client = TestClient(app)


def _setup(claim_a_status="insurer_review"):
    with SessionLocal() as s:
        insurer_a = Insurer(name="理赔保司A"); insurer_b = Insurer(name="理赔保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_a = InsurancePlan(insurer="理赔保司A", name="方案A", insurer_id=insurer_a.id)
        plan_b = InsurancePlan(insurer="理赔保司B", name="方案B", insurer_id=insurer_b.id)
        s.add(plan_a); s.add(plan_b); s.flush()
        enterprise = Enterprise(name="理赔测试企业"); s.add(enterprise); s.flush()
        policy_a = Policy(policy_no="POL-CLAIM-A", enterprise_id=enterprise.id, plan_id=plan_a.id, premium=100)
        policy_b = Policy(policy_no="POL-CLAIM-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(policy_a); s.add(policy_b); s.flush()
        person_a = InsuredPerson(enterprise_id=enterprise.id, name="王五", id_number="340123199001011236", status="active", policy_id=policy_a.id)
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="赵六", id_number="340123199001011237", status="active", policy_id=policy_b.id)
        s.add(person_a); s.add(person_b); s.flush()
        claim_a = Claim(enterprise_id=enterprise.id, person_id=person_a.id, policy_id=policy_a.id,
                        claim_no="CLM-A", status=claim_a_status, current_handler="保险公司理赔岗")
        claim_b = Claim(enterprise_id=enterprise.id, person_id=person_b.id, policy_id=policy_b.id,
                        claim_no="CLM-B", status="insurer_review", current_handler="保险公司理赔岗")
        claim_early = Claim(enterprise_id=enterprise.id, person_id=person_a.id, policy_id=policy_a.id,
                            claim_no="CLM-EARLY", status="collecting", current_handler="企业经办人")
        s.add(claim_a); s.add(claim_b); s.add(claim_early); s.flush()
        user_a = User(username="claim_insurer_a", password_hash=pwd.hash("test1234"), name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return claim_a.id, claim_b.id, claim_early.id


def _headers():
    login = client.post("/api/auth/login", json={"username": "claim_insurer_a", "password": "test1234", "portal": "insurer"})
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


def test_insurer_sees_only_own_insurer_review_stage_claims():
    claim_a_id, claim_b_id, claim_early_id = _setup()
    resp = client.get("/api/claims", headers=_headers())
    assert resp.status_code == 200
    ids = {row["id"] for row in resp.json()}
    assert claim_a_id in ids
    assert claim_b_id not in ids
    assert claim_early_id not in ids


def test_insurer_can_approve_own_claim_at_insurer_review():
    claim_a_id, claim_b_id, claim_early_id = _setup()
    resp = client.patch(f"/api/claims/{claim_a_id}/status", json={"status": "approved", "approved_amount": 5000}, headers=_headers())
    assert resp.status_code == 200
    assert resp.json()["status"] == "approved"


def test_insurer_cannot_approve_other_insurer_claim():
    claim_a_id, claim_b_id, claim_early_id = _setup()
    resp = client.patch(f"/api/claims/{claim_b_id}/status", json={"status": "approved", "approved_amount": 5000}, headers=_headers())
    assert resp.status_code == 403


def test_insurer_cannot_act_on_early_stage_claim():
    claim_a_id, claim_b_id, claim_early_id = _setup()
    resp = client.patch(f"/api/claims/{claim_early_id}/status", json={"status": "submitted"}, headers=_headers())
    assert resp.status_code == 403


def test_insurer_cannot_skip_to_paid():
    claim_a_id, claim_b_id, claim_early_id = _setup()
    resp = client.patch(f"/api/claims/{claim_a_id}/status", json={"status": "paid"}, headers=_headers())
    assert resp.status_code in (403, 409)
```

- [ ] **Step 6: Run tests, type-check, build**

```bash
python3 tests/insurer_claims_scope_test.py 2>&1 | tail -15 || python3 -m pytest tests/insurer_claims_scope_test.py -v
cd web && npx vue-tsc -b --noEmit && npm run build
```

Expected: all 5 tests pass, no type errors, build succeeds.

- [ ] **Step 7: Commit**

```bash
git add backend/routers/claims.py backend/services/claims.py \
  web/src/views/insurer-portal/InsurerPortalView.vue web/src/api/insurerPortal.ts \
  tests/insurer_claims_scope_test.py
git commit -m "feat: insurer portal 理赔管理 module reusing insurer_review claim node"
```

---

## Task 12: Cross-insurer isolation sweep, Java mirror note, final verification

**Files:**
- Create: `tests/insurer_full_isolation_smoke.py`
- Modify: `docs/ai-handoffs/insurer-portal.md` (new handoff record, per CLAUDE.md)

**Interfaces:**
- Consumes: every insurer-scoped endpoint from Tasks 3, 5-11.
- Produces: one end-to-end smoke test exercising the full "insurer A cannot touch insurer B's data" story across every module in one place, plus the handoff doc CLAUDE.md requires for this task.

- [ ] **Step 1: Write the end-to-end isolation smoke test**

`tests/insurer_full_isolation_smoke.py`:

```python
"""End-to-end insurer-portal isolation sweep: two insurers, two enterprises,
one of each module's record type, assert every cross-insurer read/write is
blocked. This complements the per-module scope tests in Tasks 6-11 with one
smoke test that exercises the same login → act → verify path a real insurer
account would take across all seven modules in one run."""
import os
import tempfile

os.environ["DATABASE_URL"] = f"sqlite:///{tempfile.mktemp(suffix='.db')}"

from fastapi.testclient import TestClient  # noqa: E402

from backend.app import app  # noqa: E402
from backend.core.db import SessionLocal  # noqa: E402
from backend.core.security import pwd  # noqa: E402
from backend.models import (  # noqa: E402
    ActualEmployer, Claim, Enterprise, Insurer, InsurancePlan, InsuredPerson,
    Invoice, Policy, User, WorkPosition,
)

client = TestClient(app)


def _build_world():
    with SessionLocal() as s:
        insurer_a = Insurer(name="全链路保司A")
        insurer_b = Insurer(name="全链路保司B")
        s.add(insurer_a); s.add(insurer_b); s.flush()
        plan_b = InsurancePlan(insurer="全链路保司B", name="B方案", insurer_id=insurer_b.id)
        s.add(plan_b); s.flush()
        enterprise = Enterprise(name="全链路企业")
        s.add(enterprise); s.flush()
        employer = ActualEmployer(enterprise_id=enterprise.id, name="全链路用工单位")
        s.add(employer); s.flush()
        position_b = WorkPosition(enterprise_id=enterprise.id, actual_employer_id=employer.id,
                                   actual_employer=employer.name, name="B岗位", occupation_class="1-3类",
                                   plan_id=plan_b.id, status="approved")
        s.add(position_b); s.flush()
        person_b = InsuredPerson(enterprise_id=enterprise.id, name="B员工", id_number="340123199001019999",
                                  position_id=position_b.id, status="active")
        policy_b = Policy(policy_no="POL-FULL-B", enterprise_id=enterprise.id, plan_id=plan_b.id, premium=100)
        s.add(person_b); s.add(policy_b); s.flush()
        person_b.policy_id = policy_b.id
        invoice_b = Invoice(enterprise_id=enterprise.id, account="premium", amount=100)
        claim_b = Claim(enterprise_id=enterprise.id, person_id=person_b.id, policy_id=policy_b.id,
                        claim_no="CLM-FULL-B", status="insurer_review", current_handler="保险公司理赔岗")
        s.add(invoice_b); s.add(claim_b); s.flush()
        user_a = User(username="full_isolation_insurer_a", password_hash=pwd.hash("test1234"),
                     name="保司A", role="insurer", insurer_id=insurer_a.id)
        s.add(user_a); s.commit()
        return {
            "position_b": position_b.id, "person_b": person_b.id, "policy_b": policy_b.id,
            "claim_b": claim_b.id,
        }


def test_insurer_a_touches_nothing_belonging_to_insurer_b():
    ids = _build_world()
    login = client.post("/api/auth/login", json={"username": "full_isolation_insurer_a", "password": "test1234", "portal": "insurer"})
    assert login.status_code == 200
    headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

    positions = client.get("/api/positions", headers=headers).json()
    assert all(row["id"] != ids["position_b"] for row in positions)

    policies = client.get("/api/policies", headers=headers).json()
    assert all(row["id"] != ids["policy_b"] for row in policies)

    invoices = client.get("/api/invoices", headers=headers).json()
    assert invoices == []

    insured = client.get("/api/insurer-portal/insured", headers=headers).json()
    assert all(row["id"] != ids["person_b"] for row in insured)

    claims = client.get("/api/claims", headers=headers).json()
    assert all(row["id"] != ids["claim_b"] for row in claims)

    settlement = client.get("/api/insurer-portal/settlement", headers=headers).json()
    assert all(row["policy_id"] != ids["policy_b"] for row in settlement["rows"])

    flag_resp = client.patch(f"/api/insured/{ids['person_b']}/insurer-flag", json={"reason": "x"}, headers=headers)
    assert flag_resp.status_code == 403

    claim_resp = client.patch(f"/api/claims/{ids['claim_b']}/status", json={"status": "approved", "approved_amount": 1}, headers=headers)
    assert claim_resp.status_code == 403

    review_resp = client.patch(f"/api/positions/{ids['position_b']}/review",
                               json={"status": "approved", "occupation_class": "1-3类"}, headers=headers)
    assert review_resp.status_code in (400, 403)

    upload_resp = client.post(f"/api/policies/{ids['policy_b']}/document/upload", headers=headers,
                              files={"file": ("t.pdf", b"%PDF fake", "application/pdf")})
    assert upload_resp.status_code == 403


if __name__ == "__main__":
    test_insurer_a_touches_nothing_belonging_to_insurer_b()
    print("insurer_full_isolation_smoke: OK")
```

- [ ] **Step 2: Run the full backend suite plus this smoke test**

```bash
cd /Users/madisonshen/Desktop/Demo
python3 -m compileall -q backend
python3 tests/insurer_full_isolation_smoke.py
python3 tests/system_smoke.py
python3 tests/security_smoke.py
```

Expected: `insurer_full_isolation_smoke: OK`, and both pre-existing baseline smoke suites still pass unchanged (confirms nothing in this plan regressed the admin/enterprise/salesperson paths).

- [ ] **Step 3: Verify the migration once more against real PostgreSQL with the finished schema**

```bash
python3 scripts/pg_migration_check.py
```

Expected: clean apply on a throwaway Neon branch, same as Task 1 Step 5 — re-run here because Tasks 2-11 may have touched model files that Alembic's autogenerate diffing could otherwise silently drift from without anyone noticing until the next unrelated migration.

- [ ] **Step 4: Sync the Java mirror backend's models/mappers (per CLAUDE.md — Java is a read-only mirror, never a second migration history)**

Locate the Java entity/mapper files mirroring `InsurancePlan`, `InsurerAccountLink`, `User`, `InsuredPerson` under `java-backend/` (same package layout as the existing mirrored entities), add the new `Insurer` entity/mapper and the new columns on the four touched tables to match Task 1's schema exactly, then:

```bash
cd java-backend && mvn -q compile
```

Expected: compiles clean. If no Maven is available in the execution environment, note this explicitly as a follow-up in the handoff doc (Step 5) rather than skipping silently — CLAUDE.md requires Java stay in sync, but doesn't require this plan's execution environment to have Maven installed.

- [ ] **Step 5: Write the handoff record**

`docs/ai-handoffs/insurer-portal.md`:

```markdown
# 保险公司独立工作台 — 交接记录

- 状态：已完成实现，待合并
- 分支：<填入实际分支名>
- 涉及范围：新 Insurer 实体表 + 迁移、用户/认证/RBAC、岗位、保单、发票、参保员工、理赔（用户/认证/RBAC/公共路由/数据库迁移，按 CLAUDE.md 需串行修改的模块）

## 修改文件
- 迁移：`backend/migrations_alembic/versions/b4f19a7d2e63_add_insurer_entity.py`（新增 insurers 表，insurance_plans/insurer_account_links/users/insured_people 加列）
- 模型：`backend/models/insurer.py`（新增），`backend/models/user.py`、`plan.py`、`finance_accounts.py`、`insured.py`（扩展）
- 核心：`backend/core/rbac.py`、`backend/core/security.py`、`backend/services/insurer_scope.py`（新增）、`backend/services/pricing.py`、`backend/services/insurer_settlement.py`（新增）
- 路由：`backend/routers/insurers.py`（新增，平台端保司主体管理）、`backend/routers/insurer_portal.py`（新增，保司工作台）、`auth.py`、`positions.py`、`reports.py`、`invoices.py`、`claims.py`、`insured.py`（收窄权限/加过滤，均为既有路由扩展，非重写）
- Web：`web/src/views/insurers/InsurerManagementView.vue`（新增）、`web/src/views/insurer-portal/InsurerPortalView.vue`（新增，7 个 tab）、路由/登录页/AppShell/App.vue 的保司端接入

## 测试
- `tests/insurer_rbac_test.py`、`insurer_admin_test.py`、`insurer_profile_test.py`、`insurer_positions_scope_test.py`、`insurer_policy_upload_scope_test.py`、`insurer_settlement_test.py`、`insurer_invoices_scope_test.py`、`insurer_flag_test.py`、`insurer_claims_scope_test.py`、`insurer_full_isolation_smoke.py`
- 迁移已过 `scripts/pg_migration_check.py`（真实 PostgreSQL）
- 现有 `system_smoke.py` / `security_smoke.py` 基线未回归

## 风险与后续
- Java 镜像后端的 Insurer 实体/Mapper 同步：<填入实际完成情况，若执行环境无 Maven 需在此明确标注为未验证编译>
- 生产部署（Render + xbbzp.com）与生产数据库迁移执行：未经用户明确授权前不得执行，需单独获得批准后再部署（CLAUDE.md）
- 保司账号的首个真实测试账号需管理员通过"保司主体管理"页面创建 Insurer 记录后，再手工创建一个 role='insurer' 的 User 并关联 insurer_id（本计划未包含"平台端创建保司账号"的独立 UI——如需要，是后续一个小任务：在现有"单位账号管理"模式基础上加一个 insurer 账号创建入口）
```

- [ ] **Step 6: Commit**

```bash
git add tests/insurer_full_isolation_smoke.py docs/ai-handoffs/insurer-portal.md
git commit -m "test: full-sweep insurer isolation smoke test + handoff record"
```

---

## Final Verification

```bash
cd /Users/madisonshen/Desktop/Demo
python3 -m compileall -q backend
git diff --check
python3 tests/system_smoke.py
python3 tests/security_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/insurer_rbac_test.py
python3 tests/insurer_admin_test.py
python3 tests/insurer_profile_test.py
python3 tests/insurer_positions_scope_test.py
python3 tests/insurer_policy_upload_scope_test.py
python3 tests/insurer_settlement_test.py
python3 tests/insurer_invoices_scope_test.py
python3 tests/insurer_flag_test.py
python3 tests/insurer_claims_scope_test.py
python3 tests/insurer_full_isolation_smoke.py
python3 scripts/pg_migration_check.py
cd web && npx vue-tsc -b --noEmit && npm run build
```

All must pass before this branch is offered for merge (per `superpowers:finishing-a-development-branch`). Do not deploy to Render or xbbzp.com production without separate, explicit user authorization for that specific deploy — this is a standing rule for this project, and doubly so here because the migration touches four tables including `users`.

## Explicitly Out of Scope

- Miniprogram changes (spec §范围边界 excludes this explicitly).
- A dedicated "create insurer account" admin UI — this plan lets an admin create the `Insurer` record (Task 4) but creating the paired `role='insurer'` `User` account is a manual DB/ops step for the first rollout, called out as a follow-up in the Task 12 handoff doc rather than built here, since the spec never asked for it and the existing 单位账号管理 pattern is the natural place to extend later.
- Any direct insurer mutation of `InsuredPerson.status` or coverage dates — the flag endpoint is intentionally the only insurer-facing write path onto that table (spec §范围边界).
- Removing the legacy `insurer` string columns from `InsurancePlan` / `InsurerAccountLink` — they stay as a display-layer transition per the spec; a future cleanup task can drop them once every read path is confirmed migrated to `insurer_id`.
