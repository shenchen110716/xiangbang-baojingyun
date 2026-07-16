# Cross-Runtime Parity and Release Phase (v4.2 Phase 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **⚠ CONTRACT-PROVISIONAL.** Written before Phases 2–5 merged. Every table and endpoint below is named from those phases' *plans*, not from shipped code. **Before Task 1, enumerate what actually merged (`git log`, `alembic heads`, `backend/models/__init__.py`, the live `/openapi.json`) and rewrite this plan's inventory to match.** Do not start until Phases 2–5 are all `merged` and no migration lock is held.

**Goal:** Bring the Java runtime image up to the merged Python schema and authorization semantics, prove the two runtimes agree field-for-field, run the full v4.2 security and migration regression, and produce a release-ready handoff.

**Architecture:** Java is a **mirror**, never an authority (`CLAUDE.md`). This phase adds entities, mappers and fail-closed controller filters that follow Alembic's shipped structure, and adds no Java migration history. The core deliverable beyond code is a *contract check*: a test that reads the Python schema and the Java mapper column list and fails when they diverge, so the next schema change cannot silently desync the mirror. That check exists because Phase 1 proved the failure mode is real and silent.

**Tech Stack:** Java 21, Spring Boot, MyBatis, Maven, JUnit 5; Python/FastAPI as the reference.

## Lesson from Phase 1 — read before writing Java

Phase 1's Java mirror shipped a real fail-open defect: `User.enterpriseRole` was initialised to `"owner"`, and because MyBatis defaults `callSettersOnNulls=false`, a NULL `enterprise_role` column left the field at `"owner"` — granting enterprise-wide access where the authoritative Python path returns 403. It was caught only at the phase gate.

The general rule this phase must apply everywhere: **a mirrored field's default must match the column's nullability, not a value that seems convenient.** For any nullable column, the Java field default must be `null`, and the authorization path must fail closed on `null`. Every scope-bearing field added in this phase needs a regression test in the shape of `EmployerScopeAccessTest.enterpriseUserWithoutEnterpriseRoleFailsClosed`.

## Global Constraints

- Base off `main` after Phases 2–5 merge. **This phase creates no migration** and must not modify anything under `backend/migrations_alembic/`. Confirm `python3 -m alembic -c alembic.ini heads` is unchanged from entry to exit.
- Python/Alembic is the sole structural authority; Java only mirrors entities, mappers and runtime reads (`CLAUDE.md`).
- Do not add `V5__*.sql` or any Flyway/Liquibase history to `java-backend/`.
- Java behaviour changes are limited to mirroring Python semantics. If a divergence is found, **fix the Java side**; only touch Python if Python is genuinely wrong, and then say so explicitly in the handoff.
- Maven is not on `PATH` in this environment; it lives at `~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn`.
- Do not upload or submit the Mini Program, and do not deploy, without per-release user authorization (`CLAUDE.md`).

---

### Task 1: Inventory What Actually Merged

**Files:**
- Create: `docs/ai-handoffs/cross-runtime-release-phase6.md`

**Interfaces:**
- Produces: the authoritative list of tables, columns and endpoints this phase must mirror.

- [ ] **Step 1: Enumerate the shipped schema**

```bash
bash scripts/ai_coordination_check.sh
python3 -m alembic -c alembic.ini heads
python3 -m alembic -c alembic.ini history | head -20
grep -n "^from\|^__all__" backend/models/__init__.py
ls backend/models/
```

- [ ] **Step 2: Enumerate the shipped endpoints**

```bash
python3 -m uvicorn backend.app:app --port 8001 &
sleep 3
curl -s http://127.0.0.1:8001/openapi.json | python3 -c "import sys,json; [print(p) for p in sorted(json.load(sys.stdin)['paths'])]"
```

- [ ] **Step 3: Enumerate the current Java surface**

```bash
find java-backend/src/main/java -name "*Mapper.java" -o -name "*Controller.java" | sort
```

- [ ] **Step 4: Write the gap table into the handoff**

Record, as a real table: every Python table with no Java entity; every Java mapper whose `COLUMNS` list is missing a shipped column; every scope-bearing endpoint with no Java fail-closed filter. **Rewrite Tasks 2–4 of this plan against that table** before writing any code — the names below are provisional.

Set `status: active`, `migration_owner: no（本阶段不建迁移）`.

- [ ] **Step 5: Commit the inventory**

```bash
git worktree add /private/tmp/xiangbang-parity -b feat/cross-runtime-parity-phase6 main
git add docs/ai-handoffs/cross-runtime-release-phase6.md
git commit -m "docs: inventory python-java parity gaps for v4.2"
```

---

### Task 2: The Contract Check That Prevents Silent Desync

**Files:**
- Create: `tests/cross_runtime_contract_test.py`

**Interfaces:**
- Consumes: SQLAlchemy metadata; the Java mapper sources as text.
- Produces: a test that fails when a Python column has no Java mapping.

- [ ] **Step 1: Write the failing contract test**

```python
"""Python 是唯一结构权威；本测试确保 Java 镜像不会在结构变更后静默失配。"""
import re, pathlib
from backend.core.db import Base
import backend.models  # noqa: F401 — 触发全部模型注册

JAVA = pathlib.Path('java-backend/src/main/java/com/xbb/baojing')

MIRRORED_TABLES = {           # Task 1 的清单填这里
    'users': 'common/UserMapper.java',
    'user_employer_scopes': 'enterprise/UserEmployerScopeMapper.java',
    'employment_facts': 'employment/EmploymentFactMapper.java',
    ...
}

def java_columns(mapper_path: str) -> set[str]:
    text = (JAVA / mapper_path).read_text(encoding='utf-8')
    body = ' '.join(re.findall(r'COLUMNS\s*=\s*(.+?);', text, re.S))
    return {m.split()[0].strip('", ') for m in body.split(',') if m.strip()}

def test_every_mirrored_table_maps_every_python_column():
    problems = []
    for table_name, mapper in MIRRORED_TABLES.items():
        table = Base.metadata.tables[table_name]
        missing = {c.name for c in table.columns} - java_columns(mapper)
        if missing:
            problems.append(f'{table_name} 的 Java 映射缺少列: {sorted(missing)} ({mapper})')
    assert not problems, '\n'.join(problems)

def test_nullable_columns_are_not_defaulted_to_a_value_in_java():
    """Phase 1 的 fail-open 教训：MyBatis callSettersOnNulls 默认 false，
    可空列若在 Java 侧带默认值，NULL 行会保留该默认值。"""
    problems = []
    for table_name, mapper in MIRRORED_TABLES.items():
        entity = (JAVA / mapper).parent / (java_entity_name(table_name) + '.java')
        text = entity.read_text(encoding='utf-8')
        for column in Base.metadata.tables[table_name].columns:
            if not column.nullable: continue
            field = snake_to_camel(column.name)
            if re.search(rf'private\s+\w+\s+{field}\s*=', text):
                problems.append(f'{entity.name}: 可空列 {column.name} 在 Java 侧带默认值')
    assert not problems, '\n'.join(problems)
```

- [ ] **Step 2: Run it and record the real gaps**

```bash
python3 tests/cross_runtime_contract_test.py
```

Expected: FAIL, listing exactly the columns Tasks 3–4 must add. This failure list is the task list.

- [ ] **Step 3: Commit the check**

```bash
git add tests/cross_runtime_contract_test.py
git commit -m "test: fail when java mirror desyncs from python schema"
```

---

### Task 3: Mirror the v4.2 Entities and Mappers

**Files:**
- Create: `java-backend/src/main/java/com/xbb/baojing/employment/` (entities + mappers per the Task 1 inventory)
- Modify: existing mappers whose `COLUMNS` lists are short

**Interfaces:**
- Consumes: the shipped Alembic column names — copy them exactly, including `snake_case` → `camelCase` aliases.

- [ ] **Step 1: Add entities with nullability-faithful defaults**

For each mirrored table: a plain entity whose field types match the column types and whose **nullable columns have no initialiser**. Add `@JsonProperty("snake_case")` on getters where the JSON contract uses snake_case, following `User.getEnterpriseRole()`.

- [ ] **Step 2: Add mappers**

Follow the `UserMapper` idiom exactly: a `COLUMNS` constant with `column AS camelAlias`, then `@Select`/`@Insert`/`@Update` referencing it. Every `INSERT`/`UPDATE` must list the new columns, or writes will silently drop them — the same class of bug as a short `COLUMNS` list.

- [ ] **Step 3: Run the contract check**

```bash
python3 tests/cross_runtime_contract_test.py
~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn -f java-backend/pom.xml test
```

Expected: contract test PASSES; Maven compiles.

- [ ] **Step 4: Commit**

```bash
git add java-backend/src/main/java
git commit -m "feat: mirror v4.2 employment and settlement schema in java"
```

---

### Task 4: Fail-Closed Java Authorization Parity

**Files:**
- Modify: the Java controllers exposing employment facts, timeliness and agent-portal reads
- Create: `java-backend/src/test/java/com/xbb/baojing/**/*Test.java`

**Interfaces:**
- Consumes: `EmployerScopeAccess` (Phase 1, already merged).

- [ ] **Step 1: Write the failing fail-closed tests first**

For every scope-bearing controller added here, mirror the proven shape:

```java
@Test
void projectManagerWithNoActiveScopeSeesNothing() {
    assertEquals(List.of(), controller.list(projectManagerWithNoScopes()));
}

@Test
void userWithoutEnterpriseRoleFailsClosed() {
    assertThrows(ApiException.class, () -> controller.list(enterpriseUserWithNullRole()));
}

@Test
void agentCannotReadAnotherAgentsCommissions() {
    assertThrows(ApiException.class, () -> portal.commissions(agentA, /* agentId */ agentB.getId()));
}
```

- [ ] **Step 2: Run and confirm failure**

```bash
~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn -f java-backend/pom.xml test
```

Expected: FAIL.

- [ ] **Step 3: Apply the filters**

Route every employer-scoped Java read through `EmployerScopeAccess.allowedEmployerIds` / `requireEmployerAccess`. An empty scope set yields an empty list and 403 on object access. Agent-portal reads derive the agent from the principal and ignore any supplied id, matching §17.1.

- [ ] **Step 4: Run and commit**

```bash
~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn -f java-backend/pom.xml test
git add java-backend/src
git commit -m "feat: apply fail-closed scope filtering across java v4.2 endpoints"
```

---

### Task 5: Full v4.2 Regression and Migration Validation

**Files:**
- Modify: `docs/ai-handoffs/cross-runtime-release-phase6.md`

- [ ] **Step 1: Run the complete matrix (§18)**

```bash
for t in id_number_test employment_model_test employment_fact_service_test \
         employment_matching_test employment_import_test employment_integration_test \
         employment_facts_smoke timeliness_engine_test timeliness_rules_test \
         timeliness_responsibility_test timeliness_model_test timeliness_smoke \
         timeliness_reporting_test timeliness_reporting_smoke \
         agent_settlement_model_test agent_settlement_service_test agent_portal_smoke \
         employer_scope_smoke employer_scope_model_test employer_scope_service_test \
         security_smoke system_smoke recharge_smoke participation_lock_smoke \
         salesperson_portal_smoke cross_runtime_contract_test; do
  printf '%-40s' "$t"; python3 "tests/$t.py" >/dev/null 2>&1 && echo PASS || echo FAIL
done
cd web && npm run build && cd ..
~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn -f java-backend/pom.xml test
python3 -m compileall -q backend
git diff --check
python3 -m alembic -c alembic.ini heads
```

Expected: every line PASS; one Alembic head, **unchanged** from entry.

- [ ] **Step 2: Validate empty-database, legacy-database and failure recovery (§18)**

```bash
TMP=$(mktemp -d)
DATABASE_URL="sqlite:///$TMP/fresh.db" python3 -m alembic -c alembic.ini upgrade head
cp data.db "$TMP/legacy.db" && DATABASE_URL="sqlite:///$TMP/legacy.db" python3 -m alembic -c alembic.ini upgrade head
python3 -m alembic -c alembic.ini upgrade head --sql > "$TMP/offline.sql"    # PostgreSQL 离线 SQL 可读性检查
```

Record the known pre-existing legacy SQLite chain issue with `96b709380f70` rather than fixing it here — it predates v4.2 and production runs PostgreSQL on a linear chain.

- [ ] **Step 3: Cross-tenant leakage sweep (§18)**

Explicitly verify, with tests already written in earlier phases plus manual spot checks: cross-enterprise, cross-employer and cross-salesperson reads all deny. Confirm no ID plaintext appears in any list response, log line, audit row or export.

- [ ] **Step 4: Mini Program checks**

Compile and preview via the WeChat devtools CLI. Verify a project manager sees only authorized employers. **Do not upload; do not submit for review.**

- [ ] **Step 5: Write the release-readiness handoff**

Set `status: review`. Record, at minimum:

- The full command matrix and its results.
- The single Alembic head and the ordered revision list added by v4.2.
- **Production secrets that must be set before deploy:** `ID_ENCRYPTION_KEY` (Phase 2) — startup fails without it — plus any integration API keys.
- New runtime dependency: `cryptography`.
- The Outbox worker's scheduling story: it must be triggered by something, or results never refresh.
- Anything still not covered: real-PostgreSQL concurrency tests, large-batch import performance, export timeouts on Render's free plan.
- An explicit statement that deploy and Mini Program upload require the user's per-release authorization.

- [ ] **Step 6: Commit**

```bash
git add docs/ai-handoffs/cross-runtime-release-phase6.md
git commit -m "docs: v4.2 release readiness and cross-runtime parity report"
```

---

### Task 6: Release (User-Authorized Only)

> This task must **not** run without the user explicitly authorizing this release. `CLAUDE.md`: 未经用户对该次发布明确授权，不部署生产环境，不改生产密钥，不上传或提交微信小程序.

- [ ] **Step 1: Confirm the deploy path and its blast radius**

`render.yaml` sets `autoDeployTrigger: commit` — **pushing `main` deploys automatically**; there is no separate deploy step. The Dockerfile's `CMD alembic upgrade head && uvicorn ...` runs the v4.2 migrations against production PostgreSQL on container start. Tell the user this before pushing, not after.

- [ ] **Step 2: Set production secrets first**

`ID_ENCRYPTION_KEY` must exist in the Render dashboard **before** the push, or `verify_production_config()` aborts startup and the service stays down. The user sets it; never set production secrets yourself.

- [ ] **Step 3: Merge, re-run the gate on the merge result, push**

Run the Task 5 matrix again **after** merging — a green branch is not a green merge.

- [ ] **Step 4: Verify the release**

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://xiangbang-baojingyun.onrender.com/api/health
curl -s https://xiangbang-baojingyun.onrender.com/openapi.json \
  | python3 -c "import sys,json; p=json.load(sys.stdin)['paths']; print(len(p)); print([x for x in p if 'timeliness' in x or 'employment' in x])"
```

Because the container only serves traffic if `alembic upgrade head` succeeded, new routes answering is itself proof the migrations applied. A 404 on a new route means the old container is still live — wait and re-probe rather than concluding success.

- [ ] **Step 5: Set every handoff to `merged` and release the migration lock**
