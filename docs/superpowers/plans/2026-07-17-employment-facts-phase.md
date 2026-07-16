# Employment Facts Phase (v4.2 Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store versioned real employment facts (真实入离职) per employee, imported through an atomic two-phase preview/confirm flow with identity protection, plus an authenticated external event API — so Phase 3 has an authoritative fact base to compute timeliness from.

**Architecture:** Three new tables mirror `SYSTEM-DESIGN-V4.2.md` §6 exactly: `EmploymentFeedbackBatch` (upload/preview/confirm lifecycle), `EmploymentFact` (immutable versioned employment periods), `EmploymentFactMatch` (identity matching workflow, kept separate so candidate noise never pollutes authoritative facts). Preview computes and returns a full row-level report without writing facts; confirm re-derives the same report under a single transaction guarded by a one-time token bound to (enterprise, uploader, file hash, preview version). Identity numbers are persisted as ciphertext plus a deterministic HMAC hash; plaintext lives only inside the import transaction. All employer-scoped reads/writes route through the Phase 1 `backend/services/employer_scopes.py` service — this phase adds no second authorization path.

**Tech Stack:** FastAPI, SQLAlchemy 2, Alembic, Pydantic, openpyxl, `cryptography` (new), SQLite smoke tests.

## Global Constraints

- Base branch off `main@6bb9fd7` or later in an external worktree; never work directly on `main`.
- This phase holds the Alembic migration lock. The new migration's `down_revision` must be exactly `d5a4c12f7b91` (the sole current head). Run `python3 -m alembic -c alembic.ini heads` first and abort if it reports anything else or more than one head.
- Python/Alembic is the only migration authority. Do **not** touch `java-backend/` — Java mirroring is Phase 6.
- Do not build Web or Mini Program views in this phase; Phase 4 owns those. Backend + import + external API only.
- Reuse Phase 1 authorization: `allowed_employer_ids`, `assert_employer_access`, `is_enterprise_owner` from `backend/services/employer_scopes.py`. Never re-derive role logic in a router.
- Reuse `backend/core/business_time.py` for business-timezone parsing and `backend/core/id_number.py` for validation. Extend those modules rather than creating parallel ones.
- 存量数据不得自动伪造真实入离职时间 (§16). Migration creates empty tables only — no backfill of facts from `InsuredPerson`.
- 纠错创建新版本并将旧版本标记为 `superseded`，不得覆盖旧值 (§6.2). Corrections never UPDATE authoritative time fields in place.
- 无真实用工事实、未匹配或冲突记录不进入正式指标 (§20.6). `pending_match`/`conflict`/`voided`/`superseded` facts must be excluded by the query helper Phase 3 consumes.
- ID plaintext must never appear in list responses, logs, audit rows, or error files — masked values only (§6.4).
- All timestamps parsed in the enterprise business timezone, stored UTC (§6.2).

### New production secret (deployment-affecting)

This phase adds the `cryptography` dependency and a required `ID_ENCRYPTION_KEY` env var. `render.yaml` must gain `- key: ID_ENCRYPTION_KEY` / `sync: false`, and the key must be set in the Render dashboard **before** the phase is deployed, or `verify_production_config()` will fail startup. Flag this to the user at merge time; do not set production secrets yourself.

---

### Task 1: Claim the Phase and Establish the Red Contract Test

**Files:**
- Modify: `docs/ai-handoffs/role-timeliness-v42.md`
- Create: `docs/ai-handoffs/employment-facts-phase2.md`
- Create: `tests/employment_facts_smoke.py`

**Interfaces:**
- Consumes: existing login + `/api/actual-employers` endpoints, and `tests/security_smoke.py` helpers.
- Produces: a black-box executable the whole phase must turn green.

- [ ] **Step 1: Run the coordination preflight**

```bash
bash scripts/ai_coordination_check.sh
python3 -m alembic -c alembic.ini heads
git log --oneline -1
```

Expected: `d5a4c12f7b91 (head)`, exactly one head, no other branch owning migrations. Abort if a second head exists.

- [ ] **Step 2: Create the worktree and branch**

```bash
git worktree add /private/tmp/xiangbang-employment-facts -b feat/employment-facts-phase2 main
cd /private/tmp/xiangbang-employment-facts
```

- [ ] **Step 3: Write the handoff declaring ownership**

Create `docs/ai-handoffs/employment-facts-phase2.md` from `docs/ai-handoffs/TEMPLATE.md` with:

```markdown
- task_id: `employment-facts-phase2`
- owner: `<agent>`
- status: `active`
- branch: `feat/employment-facts-phase2`
- worktree: `/private/tmp/xiangbang-employment-facts`
- base_commit: `<main HEAD sha>`
- migration_owner: `yes（Phase 2 独占）`
- depends_on: `role-timeliness-v42 Phase 1（已合并发布）`

## Active Phase 2 Scope

- `employment_feedback_batches` / `employment_facts` / `employment_fact_matches` 迁移与模型
- `backend/core/id_number.py` 身份证密文与确定性哈希
- `backend/services/employment_facts.py`、`employment_matching.py`、`employment_import.py`
- `backend/routers/employment_facts.py` 与 `backend/routers/integrations.py` 外部事件认证
- 新依赖 `cryptography` 与新生产密钥 `ID_ENCRYPTION_KEY`（含 `render.yaml`）
```

Set `role-timeliness-v42.md` `migration_owner` to reference that Phase 2 now holds the lock.

- [ ] **Step 4: Write the failing black-box test**

Create `tests/employment_facts_smoke.py`. Copy the server-lifecycle and `call_json` helpers from `tests/employer_scope_smoke.py` (same pattern, isolated temp SQLite). Core assertions:

```python
def test_two_phase_import_is_atomic_and_versioned(ctx):
    owner = login('scope_owner')
    employer = create_actual_employer(owner, '项目 A')

    # 预览不写事实
    preview = post_file(owner, '/api/employment-feedback/import/preview', rows=[
        ['项目 A', 'E001', '张三', '340123199001011234', '2026-03-01', '', '2026-03-02', 'EXT-1', ''],
        ['项目 A', 'E002', '李四', 'BAD-ID',            '2026-03-01', '', '2026-03-02', 'EXT-2', ''],
    ])
    assert preview['valid_rows'] == 1 and preview['invalid_rows'] == 1
    assert get_json(owner, '/api/employment-facts')['items'] == []

    # 有阻断错误时禁止确认
    assert post(owner, '/api/employment-feedback/import/confirm',
                {'batch_id': preview['batch_id'], 'confirm_token': preview['confirm_token']}).status == 400

    # 全部合法后原子确认
    ok = post_file(owner, '/api/employment-feedback/import/preview', rows=[
        ['项目 A', 'E001', '张三', '340123199001011234', '2026-03-01', '', '2026-03-02', 'EXT-1', ''],
    ])
    assert post(owner, '/api/employment-feedback/import/confirm',
                {'batch_id': ok['batch_id'], 'confirm_token': ok['confirm_token']}).status == 200
    facts = get_json(owner, '/api/employment-facts')['items']
    assert len(facts) == 1 and facts[0]['status'] == 'active'
    assert facts[0]['id_number'] == '340123********1234'   # 脱敏，永不返回原文

    # 令牌一次性：重复确认不产生重复事实
    assert post(owner, '/api/employment-feedback/import/confirm',
                {'batch_id': ok['batch_id'], 'confirm_token': ok['confirm_token']}).status == 409
    assert len(get_json(owner, '/api/employment-facts')['items']) == 1


def test_correction_creates_new_version_and_supersedes_old(ctx):
    owner = login('scope_owner')
    fact_id = seed_confirmed_fact(owner, hire='2026-03-01')
    res = patch(owner, f'/api/employment-facts/{fact_id}/correct',
                {'actual_hire_at': '2026-03-05', 'reason': '入职时间录入错误'})
    assert res.status == 200
    new_id = res.json()['id']
    assert new_id != fact_id
    assert res.json()['revision_no'] == 2
    assert res.json()['previous_version_id'] == fact_id
    assert get_json(owner, f'/api/employment-facts/{fact_id}')['status'] == 'superseded'
    # 旧版本仍可审计，且不进入正式口径
    assert [f['id'] for f in get_json(owner, '/api/employment-facts')['items']] == [new_id]


def test_project_manager_is_confined_to_authorized_employers(ctx):
    owner = login('scope_owner')
    manager = create_project_manager(owner)
    employer_a = create_actual_employer(owner, '项目 A')
    employer_b = create_actual_employer(owner, '项目 B')
    grant_scope(owner, manager, employer_a)

    assert post_file(manager, '/api/employment-feedback/import/preview',
                     rows=[['项目 B', 'E9', '王五', '340123199001011234', '2026-03-01', '', '2026-03-02', 'X', '']]
                     ).json()['rows'][0]['errors'] != []       # 越权单位整行阻断
    assert ids(get_json(manager, '/api/employment-facts')['items']) <= ids_of(employer_a)


def test_duplicate_source_event_id_is_idempotent(ctx):
    # 重复 source_event_id 不产生重复事实（§17.2）
    ...
```

- [ ] **Step 5: Run it and confirm it fails**

```bash
python3 tests/employment_facts_smoke.py
```

Expected: FAIL — `/api/employment-feedback/import/preview` returns 404 (router absent).

- [ ] **Step 6: Commit the red contract**

```bash
git add tests/employment_facts_smoke.py docs/ai-handoffs/
git commit -m "test: define employment fact import and versioning contract"
```

---

### Task 2: Identity Protection Core

**Files:**
- Modify: `backend/core/id_number.py`
- Modify: `backend/core/config.py`
- Modify: `requirements.txt`
- Modify: `render.yaml`
- Test: `tests/id_number_test.py`

**Interfaces:**
- Consumes: `config.SECRET_KEY` pattern for env-var secrets.
- Produces:
  - `id_hash(value: str) -> str` — deterministic HMAC-SHA256 hex, stable across processes, used for matching.
  - `id_encrypt(value: str) -> str` / `id_decrypt(token: str) -> str` — Fernet ciphertext, non-deterministic.
  - `mask_id_number(value: str) -> str` — `'340123********1234'`, the only form allowed in responses/logs.

- [ ] **Step 1: Write the failing tests**

Create `tests/id_number_test.py`:

```python
import os, sys
os.environ.setdefault('ID_ENCRYPTION_KEY', 'x' * 44)
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from backend.core.id_number import id_hash, id_encrypt, id_decrypt, mask_id_number

RAW = '340123199001011234'

def test_hash_is_deterministic_and_not_reversible():
    assert id_hash(RAW) == id_hash(RAW)
    assert id_hash(RAW) != id_hash('340123199001011235')
    assert RAW not in id_hash(RAW)
    assert len(id_hash(RAW)) == 64

def test_hash_normalises_case_and_whitespace():
    assert id_hash(' 34012319900101123x ') == id_hash('34012319900101123X')

def test_encrypt_roundtrips_and_is_not_deterministic():
    a, b = id_encrypt(RAW), id_encrypt(RAW)
    assert a != b                      # Fernet 含随机 IV
    assert id_decrypt(a) == RAW and id_decrypt(b) == RAW
    assert RAW not in a

def test_mask_hides_the_birth_date_segment():
    assert mask_id_number(RAW) == '340123********1234'
    assert mask_id_number('') == ''
    assert mask_id_number('123') == '***'

if __name__ == '__main__':
    for name, fn in sorted(globals().items()):
        if name.startswith('test_'): fn(); print(f'  {name} ok')
    print('id_number tests passed')
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/id_number_test.py
```

Expected: FAIL — `ImportError: cannot import name 'id_hash'`.

- [ ] **Step 3: Add the dependency**

Append to `requirements.txt`:

```text
cryptography
```

Then `python3 -m pip install -r requirements.txt`.

- [ ] **Step 4: Add the key to config**

In `backend/core/config.py`, after `SECRET_KEY`:

```python
_DEV_ID_KEY = "dev-only-id-key-change-me-0000000000000000000"
ID_ENCRYPTION_KEY = os.getenv("ID_ENCRYPTION_KEY", _DEV_ID_KEY)
```

Inside `verify_production_config()`'s `problems` block, alongside the JWT checks:

```python
    if ID_ENCRYPTION_KEY == _DEV_ID_KEY:
        problems.append("ID_ENCRYPTION_KEY 未设置或仍为开发默认值")
```

- [ ] **Step 5: Implement the primitives**

In `backend/core/id_number.py`:

```python
import base64, hashlib, hmac
from cryptography.fernet import Fernet
from .config import ID_ENCRYPTION_KEY


def _normalise(value: str) -> str:
    return (value or '').strip().upper()


def _fernet() -> Fernet:
    key = base64.urlsafe_b64encode(hashlib.sha256(ID_ENCRYPTION_KEY.encode()).digest())
    return Fernet(key)


def id_hash(value: str) -> str:
    """确定性哈希，仅用于匹配与唯一性判断，不可逆。"""
    return hmac.new(ID_ENCRYPTION_KEY.encode(), _normalise(value).encode(), hashlib.sha256).hexdigest()


def id_encrypt(value: str) -> str:
    return _fernet().encrypt(_normalise(value).encode()).decode()


def id_decrypt(token: str) -> str:
    return _fernet().decrypt(token.encode()).decode()


def mask_id_number(value: str) -> str:
    raw = _normalise(value)
    if len(raw) < 10: return '*' * len(raw)
    return f'{raw[:6]}{"*" * (len(raw) - 10)}{raw[-4:]}'
```

- [ ] **Step 6: Run the tests**

```bash
python3 tests/id_number_test.py
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Add the production key to render.yaml**

Under `envVars`:

```yaml
      - key: ID_ENCRYPTION_KEY
        sync: false
```

- [ ] **Step 8: Commit**

```bash
git add backend/core/id_number.py backend/core/config.py requirements.txt render.yaml tests/id_number_test.py
git commit -m "feat: add identity hashing, encryption and masking primitives"
```

---

### Task 3: Schema and Models

**Files:**
- Create: `backend/migrations_alembic/versions/<rev>_add_employment_facts.py`
- Create: `backend/models/employment.py`
- Modify: `backend/models/__init__.py`
- Modify: `backend/core/migrations.py`
- Test: `tests/employment_model_test.py`

**Interfaces:**
- Consumes: `d5a4c12f7b91` as `down_revision`; `Base` from `backend/core/db.py`.
- Produces: `EmploymentFeedbackBatch`, `EmploymentFact`, `EmploymentFactMatch` ORM classes exported from `backend.models`.

- [ ] **Step 1: Write the failing model test**

Create `tests/employment_model_test.py` asserting the columns and constraints from §6, following the shape of `tests/employer_scope_model_test.py`:

```python
def test_fact_requires_leave_after_hire():
    with pytest_raises_integrity():
        session.add(EmploymentFact(enterprise_id=1, actual_employer_id=1,
                                   actual_hire_at=dt('2026-03-05'), actual_leave_at=dt('2026-03-01'),
                                   status='active', revision_no=1))
        session.commit()

def test_source_event_id_is_unique_per_source():
    # 同一 source_event_id 第二次插入必须失败（§6.2 幂等）
    ...

def test_only_one_active_version_per_lineage():
    # 同一 previous_version_id 链上只允许一条 active
    ...
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/employment_model_test.py
```

Expected: FAIL — `ImportError: cannot import name 'EmploymentFact'`.

- [ ] **Step 3: Generate the migration**

```bash
python3 -m alembic -c alembic.ini revision -m "add employment facts"
```

Set `down_revision = "d5a4c12f7b91"`. Follow the SQLite-offline-vs-batch pattern already proven in `d5a4c12f7b91_add_employer_scopes.py`:

```python
def upgrade() -> None:
    op.create_table(
        "employment_feedback_batches",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=True),
        sa.Column("source_type", sa.String(20), nullable=False),
        sa.Column("source_filename", sa.String(255), nullable=False, server_default=""),
        sa.Column("source_file_hash", sa.String(64), nullable=False, server_default=""),
        sa.Column("reported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("imported_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("imported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("total_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("valid_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("invalid_rows", sa.Integer, nullable=False, server_default="0"),
        sa.Column("status", sa.String(32), nullable=False, server_default="uploaded"),
        sa.Column("preview_version", sa.Integer, nullable=False, server_default="0"),
        sa.Column("confirm_token_digest", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "source_type IN ('manual_import','api','system_sync')", name="ck_batch_source_type"),
        sa.CheckConstraint(
            "status IN ('uploaded','previewed','confirmed','imported_pending_calculation',"
            "'completed','rejected','failed')", name="ck_batch_status"),
    )
    # 同一企业、来源、文件哈希不得重复确认（§6.1）
    op.create_index("ux_batch_confirmed_file", "employment_feedback_batches",
                    ["enterprise_id", "source_type", "source_file_hash"], unique=True,
                    sqlite_where=sa.text("status = 'confirmed' AND source_file_hash != ''"),
                    postgresql_where=sa.text("status = 'confirmed' AND source_file_hash != ''"))

    op.create_table(
        "employment_facts",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=False, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True, index=True),
        sa.Column("external_employee_no", sa.String(64), nullable=False, server_default=""),
        sa.Column("external_employment_id", sa.String(64), nullable=False, server_default=""),
        sa.Column("id_number_hash", sa.String(64), nullable=False, server_default=""),
        sa.Column("id_number_cipher", sa.Text, nullable=False, server_default=""),
        sa.Column("person_name", sa.String(64), nullable=False, server_default=""),
        sa.Column("actual_hire_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actual_leave_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("feedback_reported_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("batch_id", sa.Integer, sa.ForeignKey("employment_feedback_batches.id"), nullable=True),
        sa.Column("source_event_id", sa.String(64), nullable=True),
        sa.Column("revision_no", sa.Integer, nullable=False, server_default="1"),
        sa.Column("previous_version_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=True),
        sa.Column("status", sa.String(20), nullable=False, server_default="active"),
        sa.Column("created_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("actual_leave_at IS NULL OR actual_leave_at > actual_hire_at",
                           name="ck_fact_leave_after_hire"),
        sa.CheckConstraint(
            "status IN ('active','superseded','pending_match','conflict','voided')",
            name="ck_fact_status"),
    )
    # source_event_id 在数据源身份范围内唯一，保证外部推送幂等（§6.2）
    op.create_index("ux_fact_source_event", "employment_facts",
                    ["enterprise_id", "source_event_id"], unique=True,
                    sqlite_where=sa.text("source_event_id IS NOT NULL"),
                    postgresql_where=sa.text("source_event_id IS NOT NULL"))
    op.create_index("ix_fact_scope_window", "employment_facts",
                    ["enterprise_id", "actual_employer_id", "actual_hire_at"])

    op.create_table(
        "employment_fact_matches",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False, index=True),
        sa.Column("match_status", sa.String(20), nullable=False),
        sa.Column("match_method", sa.String(32), nullable=False),
        sa.Column("candidate_person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("matched_person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("confidence", sa.Float, nullable=False, server_default="0"),
        sa.Column("reason", sa.String(255), nullable=False, server_default=""),
        sa.Column("confirmed_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("match_status IN ('matched','pending','ambiguous','rejected')",
                           name="ck_match_status"),
        sa.CheckConstraint(
            "match_method IN ('external_employment_id','identity_hire','employee_no','manual')",
            name="ck_match_method"),
    )


def downgrade() -> None:
    op.drop_table("employment_fact_matches")
    op.drop_index("ix_fact_scope_window", table_name="employment_facts")
    op.drop_index("ux_fact_source_event", table_name="employment_facts")
    op.drop_table("employment_facts")
    op.drop_index("ux_batch_confirmed_file", table_name="employment_feedback_batches")
    op.drop_table("employment_feedback_batches")
```

No data backfill: 存量数据不得自动伪造真实入离职时间 (§16).

- [ ] **Step 4: Write the models**

Create `backend/models/employment.py` mirroring the columns above with SQLAlchemy 2 `Mapped`/`mapped_column`, following `backend/models/enterprise.py` style. Export all three from `backend/models/__init__.py`.

- [ ] **Step 5: Keep the SQLite runtime bridge aligned**

`backend/core/migrations.py` `run_sqlite_bridge_migrations` must create these tables on legacy SQLite files, matching how `d5a4c12f7b91`'s tables were bridged. Verify a legacy `data.db` boots.

- [ ] **Step 6: Verify upgrade and downgrade on a temp database**

```bash
TMP=$(mktemp -d)
DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini upgrade head
DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini downgrade d5a4c12f7b91
DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini upgrade head
python3 -m alembic -c alembic.ini heads
```

Expected: all exit 0; exactly one head, the new revision.

- [ ] **Step 7: Run the model tests and commit**

```bash
python3 tests/employment_model_test.py
git add backend/models backend/migrations_alembic backend/core/migrations.py tests/employment_model_test.py
git commit -m "feat: add employment fact schema and models"
```

---

### Task 4: Fact Service — Versioning, Corrections, Authoritative Queries

**Files:**
- Create: `backend/services/employment_facts.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/employment_fact_service_test.py`

**Interfaces:**
- Consumes: `EmploymentFact` (Task 3); `allowed_employer_ids`, `assert_employer_access` (Phase 1).
- Produces — **Phase 3 consumes exactly these**:
  - `active_facts(session, user, *, employer_ids=None, since=None, until=None) -> list[EmploymentFact]` — scope-filtered, `status == 'active'` only.
  - `correct_fact(session, user, fact_id: int, *, actual_hire_at=None, actual_leave_at=None, reason: str) -> EmploymentFact` — returns the **new** version.
  - `serialize_fact(fact) -> dict` — always masks the ID number.
  - `FACT_EXCLUDED_STATUSES: frozenset[str] = frozenset({'superseded','pending_match','conflict','voided'})`

- [ ] **Step 1: Write the failing service tests**

```python
def test_correct_creates_new_version_and_supersedes_previous(session):
    original = make_fact(session, hire='2026-03-01', revision_no=1)
    new = correct_fact(session, owner, original.id, actual_hire_at=dt('2026-03-05'), reason='录入错误')
    assert new.id != original.id
    assert new.revision_no == 2 and new.previous_version_id == original.id
    assert new.actual_hire_at == dt('2026-03-05')
    assert original.status == 'superseded'
    assert original.actual_hire_at == dt('2026-03-01')   # 旧值绝不被覆盖

def test_active_facts_excludes_non_authoritative_statuses(session):
    for st in ('superseded', 'pending_match', 'conflict', 'voided'):
        make_fact(session, status=st)
    keep = make_fact(session, status='active')
    assert [f.id for f in active_facts(session, owner)] == [keep.id]

def test_active_facts_is_confined_to_authorized_employers(session):
    a, b = make_fact(session, employer=employer_a), make_fact(session, employer=employer_b)
    assert [f.id for f in active_facts(session, manager_scoped_to_a)] == [a.id]

def test_correct_rejects_unauthorized_employer(session):
    fact = make_fact(session, employer=employer_b)
    with raises_http(403):
        correct_fact(session, manager_scoped_to_a, fact.id, actual_leave_at=dt('2026-04-01'), reason='x')

def test_serialize_never_leaks_plaintext_id(session):
    fact = make_fact(session, id_number='340123199001011234')
    out = serialize_fact(fact)
    assert out['id_number'] == '340123********1234'
    assert '340123199001011234' not in repr(out)
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/employment_fact_service_test.py
```

Expected: FAIL — module not found.

- [ ] **Step 3: Implement the service**

Create `backend/services/employment_facts.py`:

```python
from datetime import datetime
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session
from ..core.business_time import business_now
from ..core.id_number import mask_id_number, id_decrypt
from ..models import EmploymentFact, User
from .employer_scopes import allowed_employer_ids, assert_employer_access

FACT_EXCLUDED_STATUSES = frozenset({'superseded', 'pending_match', 'conflict', 'voided'})


def active_facts(session: Session, user: User, *, employer_ids=None, since=None, until=None):
    stmt = select(EmploymentFact).where(EmploymentFact.status == 'active')
    allowed = allowed_employer_ids(session, user)
    if allowed is not None:
        if not allowed: return []
        stmt = stmt.where(EmploymentFact.actual_employer_id.in_(allowed))
    if user.role == 'enterprise':
        stmt = stmt.where(EmploymentFact.enterprise_id == user.enterprise_id)
    if employer_ids is not None:
        stmt = stmt.where(EmploymentFact.actual_employer_id.in_(employer_ids))
    if since is not None: stmt = stmt.where(EmploymentFact.actual_hire_at >= since)
    if until is not None: stmt = stmt.where(EmploymentFact.actual_hire_at <= until)
    return list(session.scalars(stmt.order_by(EmploymentFact.id)))


def correct_fact(session, user, fact_id, *, actual_hire_at=None, actual_leave_at=None, reason):
    if not (reason or '').strip(): raise HTTPException(400, '修正必须填写原因')
    old = session.get(EmploymentFact, fact_id)
    if not old: raise HTTPException(404, '用工事实不存在')
    if old.status != 'active': raise HTTPException(409, '只能修正当前有效版本')
    assert_employer_access(session, user, old.actual_employer_id)
    hire = actual_hire_at or old.actual_hire_at
    leave = actual_leave_at if actual_leave_at is not None else old.actual_leave_at
    if leave is not None and leave <= hire: raise HTTPException(400, '真实离职时间必须晚于真实入职时间')
    new = EmploymentFact(
        enterprise_id=old.enterprise_id, actual_employer_id=old.actual_employer_id,
        person_id=old.person_id, external_employee_no=old.external_employee_no,
        external_employment_id=old.external_employment_id, id_number_hash=old.id_number_hash,
        id_number_cipher=old.id_number_cipher, person_name=old.person_name,
        actual_hire_at=hire, actual_leave_at=leave,
        feedback_reported_at=old.feedback_reported_at, batch_id=old.batch_id,
        source_event_id=None,                      # 新版本不复用幂等键
        revision_no=old.revision_no + 1, previous_version_id=old.id,
        status='active', created_by=user.id, created_at=business_now())
    old.status = 'superseded'                       # 只改状态，绝不覆盖旧时间值
    session.add(new); session.flush()
    return new


def serialize_fact(fact: EmploymentFact) -> dict:
    return {
        'id': fact.id, 'enterprise_id': fact.enterprise_id,
        'actual_employer_id': fact.actual_employer_id, 'person_id': fact.person_id,
        'person_name': fact.person_name,
        'id_number': mask_id_number(id_decrypt(fact.id_number_cipher)) if fact.id_number_cipher else '',
        'external_employee_no': fact.external_employee_no,
        'external_employment_id': fact.external_employment_id,
        'actual_hire_at': fact.actual_hire_at, 'actual_leave_at': fact.actual_leave_at,
        'feedback_reported_at': fact.feedback_reported_at,
        'revision_no': fact.revision_no, 'previous_version_id': fact.previous_version_id,
        'status': fact.status, 'batch_id': fact.batch_id, 'created_at': fact.created_at,
    }
```

- [ ] **Step 4: Run the tests and commit**

```bash
python3 tests/employment_fact_service_test.py
git add backend/services/employment_facts.py backend/services/__init__.py tests/employment_fact_service_test.py
git commit -m "feat: add employment fact versioning and scoped queries"
```

---

### Task 5: Identity Matching Service

**Files:**
- Create: `backend/services/employment_matching.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/employment_matching_test.py`

**Interfaces:**
- Consumes: `id_hash` (Task 2); `EmploymentFactMatch` (Task 3).
- Produces:
  - `match_person(session, *, enterprise_id, actual_employer_id, external_employment_id, id_number, actual_hire_at, external_employee_no) -> MatchResult`
  - `MatchResult = namedtuple('MatchResult', 'status method person_id confidence reason')` with `status ∈ {matched, pending, ambiguous, rejected}`.

- [ ] **Step 1: Write the failing tests covering the §6.4 priority ladder**

```python
def test_priority_1_external_employment_id_wins(session):
    r = match_person(session, external_employment_id='EXT-1', id_number=OTHER_ID, ...)
    assert r.status == 'matched' and r.method == 'external_employment_id'

def test_priority_2_identity_plus_hire_date(session):
    r = match_person(session, external_employment_id='', id_number=RAW, actual_hire_at=dt('2026-03-01'), ...)
    assert r.status == 'matched' and r.method == 'identity_hire'

def test_priority_3_employee_no_within_employer(session):
    r = match_person(session, external_employment_id='', id_number='', external_employee_no='E001', ...)
    assert r.status == 'matched' and r.method == 'employee_no'

def test_multiple_candidates_are_ambiguous_not_matched(session):
    make_person(session, id_number=RAW); make_person(session, id_number=RAW)
    assert match_person(session, id_number=RAW, ...).status == 'ambiguous'

def test_no_candidate_is_pending_for_manual_match(session):
    assert match_person(session, id_number=UNKNOWN_ID, ...).status == 'pending'

def test_match_never_crosses_enterprise_or_employer(session):
    make_person(session, enterprise_id=OTHER_ENTERPRISE, id_number=RAW)
    assert match_person(session, enterprise_id=MY_ENTERPRISE, id_number=RAW, ...).status == 'pending'
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/employment_matching_test.py
```

Expected: FAIL — module not found.

- [ ] **Step 3: Implement the ladder**

Each lookup is scoped by `enterprise_id` (and `actual_employer_id` for priority 3). Identity lookup uses `id_hash(id_number)` against `InsuredPerson`, never plaintext comparison. Exactly one candidate → `matched`; more than one → `ambiguous`; zero → fall through to the next rung; nothing matches → `pending`.

- [ ] **Step 4: Run and commit**

```bash
python3 tests/employment_matching_test.py
git add backend/services/employment_matching.py backend/services/__init__.py tests/employment_matching_test.py
git commit -m "feat: add employment fact identity matching ladder"
```

---

### Task 6: Two-Phase Atomic Import

**Files:**
- Create: `backend/services/employment_import.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/employment_import_test.py`

**Interfaces:**
- Consumes: `match_person` (Task 5), `id_hash`/`id_encrypt`/`mask_id_number` (Task 2), `assert_employer_access` (Phase 1), `_read_import_rows` pattern from `backend/routers/insured.py:194`.
- Produces:
  - `preview_import(session, user, *, enterprise_id, filename, content: bytes) -> dict` — writes a batch row at `previewed`, **no facts**, returns `{batch_id, confirm_token, preview_version, total_rows, valid_rows, invalid_rows, rows: [...]}` where each row carries `{row_no, errors: [...], warnings: [...], match_status, masked_id}`.
  - `confirm_import(session, user, *, batch_id, confirm_token) -> dict` — single transaction; returns `{batch_id, status, created_facts}`.

- [ ] **Step 1: Write the failing tests**

```python
def test_preview_writes_no_facts(session):
    out = preview_import(session, owner, enterprise_id=1, filename='a.xlsx', content=book([GOOD_ROW]))
    assert out['valid_rows'] == 1
    assert session.scalars(select(EmploymentFact)).all() == []

def test_confirm_is_blocked_while_any_blocking_error_remains(session):
    out = preview_import(session, owner, ..., content=book([GOOD_ROW, BAD_ID_ROW]))
    with raises_http(400):
        confirm_import(session, owner, batch_id=out['batch_id'], confirm_token=out['confirm_token'])
    assert session.scalars(select(EmploymentFact)).all() == []      # 禁止部分确认

def test_confirm_token_is_single_use(session):
    out = preview_import(session, owner, ..., content=book([GOOD_ROW]))
    confirm_import(session, owner, batch_id=out['batch_id'], confirm_token=out['confirm_token'])
    with raises_http(409):
        confirm_import(session, owner, batch_id=out['batch_id'], confirm_token=out['confirm_token'])
    assert len(session.scalars(select(EmploymentFact)).all()) == 1

def test_token_is_bound_to_uploader_and_file_hash(session):
    out = preview_import(session, owner, ..., content=book([GOOD_ROW]))
    with raises_http(403):
        confirm_import(session, other_owner, batch_id=out['batch_id'], confirm_token=out['confirm_token'])

def test_stale_preview_version_is_rejected(session):
    first = preview_import(session, owner, ..., content=book([GOOD_ROW]))
    preview_import(session, owner, ..., content=book([GOOD_ROW, ANOTHER]))   # 重新预览 → version+1
    with raises_http(409):
        confirm_import(session, owner, batch_id=first['batch_id'], confirm_token=first['confirm_token'])

def test_confirm_is_atomic_on_mid_write_failure(session, monkeypatch):
    out = preview_import(session, owner, ..., content=book([GOOD_ROW, GOOD_ROW_2]))
    monkeypatch.setattr('backend.services.employment_import._write_fact', raise_on_second_call)
    with raises(RuntimeError):
        confirm_import(session, owner, batch_id=out['batch_id'], confirm_token=out['confirm_token'])
    assert session.scalars(select(EmploymentFact)).all() == []      # 全部回滚
    assert batch(session, out['batch_id']).status == 'previewed'    # 令牌未被消耗

def test_same_file_hash_cannot_be_confirmed_twice(session):
    # §6.1 相同企业、来源和文件哈希不得重复确认
    ...

def test_project_manager_rows_outside_scope_are_blocking_errors(session):
    out = preview_import(session, manager_scoped_to_a, ..., content=book([ROW_FOR_EMPLOYER_B]))
    assert out['rows'][0]['errors'] and out['valid_rows'] == 0
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/employment_import_test.py
```

Expected: FAIL — module not found.

- [ ] **Step 3: Implement preview**

Pipeline per §7.2, in order: read file (reuse the 10MB guard and `_read_import_rows` logic — extract it to `backend/services/employment_import.py` only if it can stay identical; otherwise import it) → validate template headers (§7.1: 实际工作单位、外部员工编号、姓名、身份证号、真实入职时间、真实离职时间、反馈时间、外部用工记录号、备注; 单位/姓名/身份证号/入职时间 required) → parse times via `business_time` → resolve employer name within the enterprise → `assert_employer_access` per row (out-of-scope ⇒ blocking error, never a silent skip) → `match_person` → duplicate/overlap/version conflict checks → persist an `EmploymentFeedbackBatch` at `status='previewed'` with `preview_version += 1`, `source_file_hash=sha256(content)`, and `confirm_token_digest=sha256(token)`.

The returned `confirm_token` is a fresh `secrets.token_urlsafe(32)`; only its digest is stored. Bind it by writing `imported_by=user.id` on the batch and checking `(batch.enterprise_id, batch.imported_by, batch.source_file_hash, batch.preview_version)` at confirm time.

- [ ] **Step 4: Implement confirm**

```python
def confirm_import(session, user, *, batch_id, confirm_token):
    batch = session.get(EmploymentFeedbackBatch, batch_id)
    if not batch: raise HTTPException(404, '导入批次不存在')
    if user.role == 'enterprise' and batch.enterprise_id != user.enterprise_id:
        raise HTTPException(403, '无权确认该批次')
    if batch.imported_by != user.id: raise HTTPException(403, '只能由上传人确认该批次')
    # 条件更新原子抢占确认权（沿用 pending_terminations 已验证的写法）
    claimed = session.execute(
        update(EmploymentFeedbackBatch)
        .where(EmploymentFeedbackBatch.id == batch_id,
               EmploymentFeedbackBatch.status == 'previewed',
               EmploymentFeedbackBatch.confirm_token_digest == _digest(confirm_token))
        .values(status='confirmed')).rowcount
    if claimed != 1: raise HTTPException(409, '该批次已确认或令牌已失效')
    rows = _rebuild_preview(session, user, batch)          # 重算，不信任客户端回传
    if any(r['errors'] for r in rows):
        raise HTTPException(400, '仍有阻断错误，请全部处理后再确认')
    ...
    batch.confirm_token_digest = None                      # 一次性
    batch.imported_at = business_now()
    batch.status = 'imported_pending_calculation'
```

Re-derive the report inside the transaction rather than trusting the client. On any exception the whole transaction rolls back, leaving the batch at `previewed` and the token usable (asserted by `test_confirm_is_atomic_on_mid_write_failure`).

`imported_pending_calculation` is the terminal status for this phase — Phase 3 advances it to `completed` after recalculation. Do not add an Outbox table here; Phase 3 owns it.

- [ ] **Step 5: Run and commit**

```bash
python3 tests/employment_import_test.py
git add backend/services/employment_import.py backend/services/__init__.py tests/employment_import_test.py
git commit -m "feat: add atomic two-phase employment fact import"
```

---

### Task 7: Employment Fact and Batch APIs

**Files:**
- Create: `backend/routers/employment_facts.py`
- Modify: `backend/routers/__init__.py`, `backend/app.py`
- Create: `backend/schemas/employment.py`
- Modify: `backend/schemas/__init__.py`

**Interfaces:**
- Consumes: Tasks 4–6 services.
- Produces the §14.2 contract exactly:

```text
GET   /api/employment-feedback/template
POST  /api/employment-feedback/import/preview
POST  /api/employment-feedback/import/confirm
GET   /api/employment-feedback/batches
GET   /api/employment-feedback/batches/{id}
GET   /api/employment-facts
GET   /api/employment-facts/{id}
PATCH /api/employment-facts/{id}/correct
GET   /api/employment-facts/unmatched
POST  /api/employment-facts/unmatched/{id}/match
```

- [ ] **Step 1: Add the failing API assertions**

Extend `tests/employment_facts_smoke.py` with: 非管理员/跨企业 403; `GET /api/employment-facts` 永不返回身份证原文; `/unmatched` 只返回 `pending_match`; 手工匹配后事实转 `active` 并记审计.

- [ ] **Step 2: Add Pydantic schemas**

Create `backend/schemas/employment.py` with `ImportPreviewOut`, `ImportRowOut`, `ImportConfirmIn`, `BatchOut`, `FactOut`, `FactCorrectIn`, `ManualMatchIn`. `FactOut.id_number: str` is documented as masked-only.

- [ ] **Step 3: Implement the router**

Mutations go through the services only — no ORM writes in the router. `GET /employment-feedback/template` builds the §7.1 XLSX with `openpyxl`, mirroring `backend/routers/insured.py:171-191` (bold header, column widths, text `number_format` on the ID column so Excel does not mangle it).

- [ ] **Step 4: Register the router**

Add to `backend/app.py`'s router includes. This phase adds **no** Web routes, so `_FRONTEND_ROUTES` is untouched.

- [ ] **Step 5: Run the smoke and the full security regression**

```bash
python3 tests/employment_facts_smoke.py
python3 tests/security_smoke.py
python3 tests/employer_scope_smoke.py
python3 tests/system_smoke.py
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add backend/routers backend/schemas backend/app.py tests/employment_facts_smoke.py
git commit -m "feat: add employment fact and import APIs"
```

---

### Task 8: External Employment Event API

**Files:**
- Create: `backend/core/api_keys.py`
- Modify: `backend/routers/integrations.py`
- Modify: `backend/migrations_alembic/versions/<rev>_add_employment_facts.py` (add `integration_api_keys` to the same migration — this phase owns one revision only)
- Modify: `backend/models/employment.py`
- Test: `tests/employment_integration_test.py`

**Interfaces:**
- Consumes: `confirm_import` internals for single-event writes; `match_person`.
- Produces the §7.3 contract:

```text
POST /api/integrations/employment-events
POST /api/integrations/employment-events/batch
GET  /api/integrations/employment-events/{source_event_id}
```

- [ ] **Step 1: Write the failing auth tests**

```python
def test_missing_or_bad_signature_is_rejected():
    assert post('/api/integrations/employment-events', body, headers={}).status == 401
    assert post(..., headers=sign(body, key='wrong')).status == 401

def test_replayed_nonce_is_rejected():
    h = sign(body, key=KEY)
    assert post(..., headers=h).status == 200
    assert post(..., headers=h).status == 409      # nonce 重放

def test_stale_timestamp_is_rejected():
    assert post(..., headers=sign(body, ts=now() - 600)).status == 401

def test_body_cannot_widen_the_authorized_scope():
    # 认证身份固定绑定企业及允许的实际工作单位，Body 不能扩大范围（§7.3）
    assert post(..., body={'enterprise_id': OTHER_ENTERPRISE, ...}, headers=sign(...)).status == 403

def test_duplicate_source_event_id_is_idempotent_not_duplicated():
    assert post(..., body=evt).status == 200
    assert post(..., body=evt).status == 200        # 幂等返回同一事实
    assert len(facts_with(evt['source_event_id'])) == 1

def test_batch_reports_row_level_errors_without_partial_commit():
    res = post('/api/integrations/employment-events/batch', body={'events': [GOOD, BAD]})
    assert res.status == 400 and res.json()['rows'][1]['errors']
    assert facts_count() == 0
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/employment_integration_test.py
```

Expected: FAIL — 404, endpoints absent.

- [ ] **Step 3: Add the key table and model**

In the same migration revision, add:

```python
    op.create_table(
        "integration_api_keys",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("name", sa.String(64), nullable=False, server_default=""),
        sa.Column("key_id", sa.String(32), nullable=False, unique=True),
        sa.Column("secret_hash", sa.String(255), nullable=False),
        sa.Column("allowed_employer_ids", sa.Text, nullable=False, server_default=""),
        sa.Column("active", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "integration_nonces",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("key_id", sa.String(32), nullable=False),
        sa.Column("nonce", sa.String(64), nullable=False),
        sa.Column("seen_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_index("ux_nonce_per_key", "integration_nonces", ["key_id", "nonce"], unique=True)
```

Add matching `downgrade()` drops. Secrets are stored via `passlib` hash, never plaintext.

- [ ] **Step 4: Implement the authenticator**

Create `backend/core/api_keys.py` with `authenticate_integration(session, request) -> IntegrationPrincipal`:

- Read `X-Api-Key-Id`, `X-Timestamp`, `X-Nonce`, `X-Signature`.
- Reject if `abs(now - timestamp) > 300` seconds → 401.
- Recompute `hmac_sha256(secret, f'{ts}\n{nonce}\n{body_sha256}')`; compare with `hmac.compare_digest` → 401 on mismatch.
- Insert the nonce; a unique-violation means replay → 409.
- Return a principal carrying `enterprise_id` and `allowed_employer_ids`; the handler must derive scope from the principal and **ignore** any scope field in the body (403 if the body disagrees).

- [ ] **Step 5: Implement the endpoints**

`source_event_id` idempotency relies on the `ux_fact_source_event` unique index: catch the integrity error and return the existing fact rather than erroring. Batch mode validates every row first and commits all-or-nothing.

- [ ] **Step 6: Run and commit**

```bash
python3 tests/employment_integration_test.py
python3 tests/security_smoke.py
git add backend/core/api_keys.py backend/routers/integrations.py backend/models backend/migrations_alembic tests/employment_integration_test.py
git commit -m "feat: add authenticated external employment event API"
```

---

### Task 9: Phase Gate and Handoff

**Files:**
- Modify: `docs/ai-handoffs/employment-facts-phase2.md`

- [ ] **Step 1: Run the complete Phase 2 verification matrix**

```bash
python3 tests/id_number_test.py
python3 tests/employment_model_test.py
python3 tests/employment_fact_service_test.py
python3 tests/employment_matching_test.py
python3 tests/employment_import_test.py
python3 tests/employment_integration_test.py
python3 tests/employment_facts_smoke.py
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/recharge_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/salesperson_portal_smoke.py
python3 -m compileall -q backend
git diff --check
python3 -m alembic -c alembic.ini heads
```

Expected: every command exits 0; `heads` reports exactly one revision (the new one).

Note: `web/npm run build` and Maven are not required — this phase changes neither. Java parity is Phase 6.

- [ ] **Step 2: Verify empty-database and legacy-database paths**

```bash
TMP=$(mktemp -d)
DATABASE_URL="sqlite:///$TMP/fresh.db" python3 -m alembic -c alembic.ini upgrade head
cp data.db $TMP/legacy.db 2>/dev/null && DATABASE_URL="sqlite:///$TMP/legacy.db" python3 -m alembic -c alembic.ini upgrade head
```

Expected: both exit 0. Record any pre-existing legacy-chain issue rather than fixing it in this phase.

- [ ] **Step 3: Update the handoff to review**

Record every commit, the migration revision, each command and its result, and these risks explicitly:

- `ID_ENCRYPTION_KEY` must be set in Render **before** deploy or production startup fails.
- `cryptography` is a new runtime dependency.
- Facts land at `imported_pending_calculation`; nothing advances them to `completed` until Phase 3 exists.
- Phase 3 must not create a migration until this phase merges and releases the lock.

Set `status: review`, keep `migration_owner: yes` until merge.

- [ ] **Step 4: Commit**

```bash
git add docs/ai-handoffs/employment-facts-phase2.md
git commit -m "docs: mark employment facts phase ready for review"
```
