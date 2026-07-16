# Timeliness Reporting Phase (v4.2 Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **⚠ CONTRACT-PROVISIONAL.** Written before Phases 2–3 merged. Its `Consumes` blocks reference the result schema and summary API those plans *promise*. **Before Task 1, re-read the merged `backend/models/timeliness.py`, `backend/services/timeliness_engine.py` and `backend/routers/timeliness.py`, and reconcile every field name and status value below against what shipped.** The merged code wins. Do not start until `timeliness-engine-phase3` is `merged`.

**Goal:** Give enterprise owners, project managers and platform admins the views, filters, stat cards, data-quality queue and audited XLSX export that turn Phase 3's results into something a human can act on.

**Architecture:** Reads only — this phase computes nothing. `backend/services/timeliness_reporting.py` aggregates `EmploymentTimelinessResult` into the §13 stat cards and detail rows behind the Phase 1 scope service, so a project manager's totals are built from their authorized employers alone rather than filtered after the fact. Export reuses the audited-download pattern (`backend/core/file_tokens.py`) rather than mounting files statically, and every cross-enterprise export writes an audit row carrying filters, exporter, time, row count and file digest (§13.4). Web views follow the existing Element Plus + Pinia + ECharts structure; the Mini Program gets the project-manager subset only.

**Tech Stack:** FastAPI, SQLAlchemy 2, openpyxl, Vue 3 + Element Plus + Pinia + ECharts, WeChat Mini Program.

## Global Constraints

- Base off `main` after Phase 3 merges. **This phase adds no migration** — if you find yourself writing one, the schema was wrong in Phase 3; stop and fix it there instead. Confirm with `python3 -m alembic -c alembic.ini heads` that the head is unchanged at merge time.
- Do not touch `java-backend/` (Phase 6).
- Every new Web route MUST be added to `_FRONTEND_ROUTES` in `backend/app.py:95` — the SPA uses an explicit whitelist, not a wildcard fallback. A route missing from it 404s on direct open and refresh. This bit the `usage-lock` task; do not repeat it.
- All reads route through `allowed_employer_ids` / `assert_employer_access`. No second authorization path.
- 项目负责人不提供全企业用工事实批次确认或授权关系维护能力 (§13.2). Those controls must be owner-only in both the API and the navigation.
- 身份证原文不得出现在页面、导出或行级错误文件中 (§15). Exports carry masked IDs only.
- 跨企业导出必须记录筛选条件、导出人、时间、行数和文件摘要 (§13.4).
- 待匹配、冲突和规则缺失记录进入数据质量队列，不进入正式及时率 (§12).
- `npm run build` (= `vue-tsc -b && vite build`) must pass — the backend serves `web/dist`, so an unbuilt change is invisible at runtime.

---

### Task 1: Claim the Phase and Establish the Red Reporting Contract

**Files:**
- Create: `docs/ai-handoffs/timeliness-reporting-phase4.md`
- Create: `tests/timeliness_reporting_smoke.py`

- [ ] **Step 1: Reconcile against merged Phase 3 (mandatory)**

```bash
bash scripts/ai_coordination_check.sh
python3 -m alembic -c alembic.ini heads
grep -n "timeliness_status\|responsibility_reason\|def summarise" backend/models/timeliness.py backend/services/timeliness_engine.py
```

Confirm the status vocabulary is exactly `timely|early|late|missing|premature|pending|unmatched|conflict` and the reason vocabulary matches §11.3. Correct this plan if not.

- [ ] **Step 2: Create the worktree and handoff**

```bash
git worktree add /private/tmp/xiangbang-timeliness-report -b feat/timeliness-reporting-phase4 main
```

Handoff: `status: active`, `migration_owner: no（本阶段不建迁移）`, `depends_on: timeliness-engine-phase3（已合并）`.

- [ ] **Step 3: Write the failing reporting test**

```python
def test_stat_cards_cover_every_required_metric(ctx):
    # §13 统计卡片至少包括这些
    s = get_json(owner, '/api/timeliness/summary')
    assert set(s) >= {
        'enrollment_due', 'enrollment_timely', 'enrollment_late', 'enrollment_missing',
        'termination_due', 'termination_timely', 'termination_premature',
        'termination_late', 'termination_missing',
        'composite_rate', 'feedback_rate', 'operator_attributable_rate',
        'coverage_gap_seconds', 'excess_premium',
    }

def test_filters_narrow_details(ctx):
    # §13.1 筛选：操作员、单位、时间段、参保/停保、手工/批量、及时状态、责任原因
    rows = get_json(owner, '/api/timeliness/details?operation_type=enrollment'
                           '&timeliness_status=late&responsibility_reason=operator_processing_late')['items']
    assert all(r['operation_type'] == 'enrollment' and r['timeliness_status'] == 'late' for r in rows)

def test_project_manager_summary_counts_only_authorized_employers(ctx):
    assert get_json(manager, '/api/timeliness/summary')['enrollment_due'] == events_in_employer_a
    assert get_json(manager, '/api/timeliness/details')['items'] == only_employer_a_rows

def test_project_manager_cannot_reach_owner_only_surfaces(ctx):
    assert get(manager, '/api/employment-feedback/batches').status == 403
    assert get(manager, '/api/employer-scopes').status == 403

def test_export_masks_identity_and_writes_an_audit_row(ctx):
    res = get(owner, '/api/timeliness/export?operation_type=enrollment')
    assert res.status == 200
    book = load_xlsx(res.content)
    assert '340123199001011234' not in all_cells(book)
    assert '340123********1234' in all_cells(book)
    audit = latest_audit(ctx, action='export')
    assert audit['row_count'] == len(rows) and audit['file_digest'] and audit['filters']

def test_data_quality_queue_lists_unmatched_and_conflict_only(ctx):
    items = get_json(owner, '/api/timeliness/data-quality')['items']
    assert {i['timeliness_status'] for i in items} <= {'unmatched', 'conflict'}
```

- [ ] **Step 4: Run and confirm failure, then commit**

```bash
python3 tests/timeliness_reporting_smoke.py     # FAIL: /api/timeliness/export → 404
git add tests/timeliness_reporting_smoke.py docs/ai-handoffs/timeliness-reporting-phase4.md
git commit -m "test: define timeliness reporting and export contract"
```

---

### Task 2: Reporting Aggregation Service

**Files:**
- Create: `backend/services/timeliness_reporting.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/timeliness_reporting_test.py`

**Interfaces:**
- Consumes: `EmploymentTimelinessResult` (Phase 3); `summarise`, `ENROLLMENT_NUMERATOR`, `TERMINATION_NUMERATOR` (Phase 3 engine — reuse them, do not restate the rate arithmetic); `allowed_employer_ids` (Phase 1).
- Produces:
  - `@dataclass ReportFilters: operator_id; employer_ids; since; until; operation_type; source; timeliness_status; responsibility_reason`
  - `summary(session, user, filters) -> dict` — the §13 stat cards.
  - `details(session, user, filters, *, limit, offset) -> tuple[list[dict], int]`
  - `data_quality(session, user, filters) -> list[dict]`

- [ ] **Step 1: Write the failing aggregation tests**

```python
def test_summary_reuses_engine_rate_definitions(session):
    # 不得在报表层重新定义口径：early 计入及时参保，premature 不计入及时停保
    seed_results(session, enrollment=['timely','early','late','missing'], termination=['timely','premature'])
    s = summary(session, owner, ReportFilters())
    assert s['enrollment_timely'] == 2        # timely + early
    assert s['termination_timely'] == 1       # premature 不计
    assert s['enrollment_due'] == 4

def test_summary_excludes_pending_unmatched_conflict_from_denominators(session):
    seed_results(session, enrollment=['pending','unmatched','conflict'])
    assert summary(session, owner, ReportFilters())['enrollment_due'] == 0

def test_only_current_results_are_counted(session):
    seed_result(session, status='superseded', timeliness_status='late')
    assert summary(session, owner, ReportFilters())['enrollment_due'] == 0

def test_operator_attributable_rate_ignores_unassigned(session):
    # 操作员可归责及时率只统计有责任人的事件
    seed_results_with_reason(session, ['unassigned_responsibility', 'operator_processing_late'])
    assert summary(session, owner, ReportFilters())['operator_attributable_rate'] == 0.0

def test_manager_scope_is_applied_in_sql_not_after(session):
    # 空授权必须直接返回零，不得先全量再过滤
    assert summary(session, manager_with_no_scope, ReportFilters())['enrollment_due'] == 0
```

- [ ] **Step 2: Run, implement, run**

Filter on `status == 'current'` always. Build the scope predicate into the SQL `WHERE` clause; an empty allowed set short-circuits to zeros.

- [ ] **Step 3: Commit**

```bash
python3 tests/timeliness_reporting_test.py
git add backend/services/timeliness_reporting.py backend/services/__init__.py tests/timeliness_reporting_test.py
git commit -m "feat: aggregate timeliness results into scoped report cards"
```

---

### Task 3: Audited XLSX Export

**Files:**
- Modify: `backend/routers/timeliness.py`
- Modify: `backend/core/audit.py`
- Test: extend `tests/timeliness_reporting_smoke.py`

**Interfaces:**
- Consumes: `details` (Task 2); `mask_id_number` (Phase 2); `backend/core/file_tokens.py` for signed download; `backend/core/audit.py` for the export record.
- Produces: `GET /api/timeliness/export` → XLSX stream.

- [ ] **Step 1: Write the failing export tests**

Already drafted in Task 1 (`test_export_masks_identity_and_writes_an_audit_row`). Add:

```python
def test_export_row_count_matches_the_filtered_details(ctx):
    rows = get_json(owner, '/api/timeliness/details?operation_type=termination')['items']
    book = load_xlsx(get(owner, '/api/timeliness/export?operation_type=termination').content)
    assert book.active.max_row - 1 == len(rows)      # 减去表头

def test_export_is_scope_confined_for_project_managers(ctx):
    book = load_xlsx(get(manager, '/api/timeliness/export').content)
    assert employer_b_name not in all_cells(book)

def test_cross_enterprise_export_requires_admin(ctx):
    assert get(owner, '/api/timeliness/export?enterprise_id=999').status == 403
```

- [ ] **Step 2: Implement the export**

Build the sheet with `openpyxl` following the `backend/routers/insured.py:171-191` template pattern (bold header, column widths, text `number_format` on the ID column). Columns: 实际工作单位、员工姓名、身份证号（脱敏）、事件类型、真实业务时间、期望保障时间、实际保障时间、及时状态、延误时长、提前时长、保障缺口、额外保费、责任人、责任原因、反馈状态。

Compute `sha256` of the generated bytes and write the audit row **before** streaming:

```python
audit_log(session, user, 'export', 'timeliness', '',
          detail=json.dumps({'filters': filters.as_dict(), 'row_count': len(rows),
                             'file_digest': digest}, ensure_ascii=False))
```

- [ ] **Step 3: Run and commit**

```bash
python3 tests/timeliness_reporting_smoke.py
python3 tests/security_smoke.py
git add backend/routers/timeliness.py backend/core/audit.py tests/timeliness_reporting_smoke.py
git commit -m "feat: add audited timeliness XLSX export"
```

---

### Task 4: Owner Web Views

**Files:**
- Create: `web/src/views/timeliness/TimelinessView.vue`
- Create: `web/src/views/timeliness/DataQualityView.vue`
- Create: `web/src/views/employment/EmploymentFactsView.vue`
- Create: `web/src/api/timeliness.ts`, `web/src/api/employmentFacts.ts`
- Modify: `web/src/api/types.ts`, `web/src/router/routes.ts`, `web/src/layouts/AppShell.vue`
- Modify: `backend/app.py` (`_FRONTEND_ROUTES`)

**Interfaces:**
- Consumes: the §14.2/§14.3 endpoints.
- Produces: routes `/timeliness`, `/timeliness/data-quality`, `/employment-facts`.

- [ ] **Step 1: Confirm shared-file ownership**

`web/src/api/types.ts`, `router/routes.ts` and `AppShell.vue` are serial-modification files per `CLAUDE.md`. Run `bash scripts/ai_coordination_check.sh` and confirm no other branch is touching them. If one is, do the backend tasks first and record the block in the handoff.

- [ ] **Step 2: Add exact frontend types**

In `web/src/api/types.ts`, mirroring the merged Pydantic schemas field-for-field:

```ts
export type TimelinessStatus =
  | 'timely' | 'early' | 'late' | 'missing' | 'premature' | 'pending' | 'unmatched' | 'conflict'

export type ResponsibilityReason =
  | 'source_feedback_late' | 'operator_processing_late' | 'system_processing_late'
  | 'insurer_confirmation_late' | 'unassigned_responsibility' | 'normal'

export interface TimelinessSummary {
  enrollment_due: number; enrollment_timely: number; enrollment_late: number
  enrollment_missing: number; termination_due: number; termination_timely: number
  termination_premature: number; termination_late: number; termination_missing: number
  composite_rate: number; feedback_rate: number; operator_attributable_rate: number
  coverage_gap_seconds: number; excess_premium: number
}

export interface TimelinessDetail {
  id: number; person_name: string; id_number: string   // 已脱敏，后端从不返回原文
  actual_employer_id: number; operation_type: 'enrollment' | 'termination'
  actual_business_at: string; expected_coverage_at: string | null
  actual_coverage_at: string | null; timeliness_status: TimelinessStatus
  delay_seconds: number; early_seconds: number; coverage_gap_seconds: number
  excess_premium: number; responsible_user_id: number | null
  responsibility_reason: ResponsibilityReason
}
```

- [ ] **Step 3: Build the views**

`TimelinessView.vue`: the §13 stat-card row, an ECharts responsibility breakdown, the §13.1 filter bar (操作员/单位/时间段/参保停保/手工批量/及时状态/责任原因) and a paged detail table with an export button. Follow the existing view structure — read `web/src/views/dashboard/HomeView.vue` for the established card/filter idiom rather than inventing one.

`EmploymentFactsView.vue`: template download → upload → preview table with per-row errors → confirm. Confirm stays disabled while any row carries a blocking error (§7.2 禁止部分确认); surface the row errors inline rather than as one opaque toast.

- [ ] **Step 4: Constrain navigation by role**

In `AppShell.vue`, owner-only entries (`/employment-facts`, `/employer-scopes`) must be hidden for `enterprise_role === 'project_manager'`, matching the Phase 1 navigation convergence. Navigation hiding is cosmetic — the API is the real gate and Task 1's tests already assert it.

- [ ] **Step 5: Whitelist the routes**

In `backend/app.py`, extend `_FRONTEND_ROUTES`:

```python
    "/timeliness", "/timeliness/data-quality", "/employment-facts",
```

- [ ] **Step 6: Build and verify**

```bash
cd web && npx vue-tsc -b --noEmit && npm run build
cd .. && python3 tests/timeliness_reporting_smoke.py
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8001/timeliness    # 期望 200，验证白名单
```

- [ ] **Step 7: Commit**

```bash
git add web/src backend/app.py
git commit -m "feat: add timeliness and employment fact web views"
```

---

### Task 5: Mini Program Project-Manager Views

**Files:**
- Create: `miniprogram/pages/timeliness/`
- Modify: `miniprogram/app.json`

**Interfaces:**
- Consumes: `/api/timeliness/summary`, `/api/timeliness/details`.

- [ ] **Step 1: Add the read-only timeliness page**

Project managers get their scoped summary and detail list only. §13.2: no batch confirm, no scope maintenance — do not add those pages. Follow the existing `miniprogram/pages/reports/` structure.

- [ ] **Step 2: Verify authorization scope on-device**

Log in as a project manager scoped to one employer and confirm the page shows that employer's numbers alone. The Mini Program calls the same scoped API, so this verifies wiring, not authorization.

- [ ] **Step 3: Compile and preview**

Use the WeChat devtools CLI against `/Users/madisonshen/Desktop/Demo/miniprogram`. **Do not upload or submit** — §18 requires 小程序语法、编译、预览 checks, and `CLAUDE.md` forbids uploading without per-release user authorization.

- [ ] **Step 4: Commit**

```bash
git add miniprogram/
git commit -m "feat: add project manager timeliness view to mini program"
```

---

### Task 6: Phase Gate and Handoff

- [ ] **Step 1: Run the full matrix**

```bash
python3 tests/timeliness_reporting_test.py
python3 tests/timeliness_reporting_smoke.py
python3 tests/timeliness_smoke.py
python3 tests/employment_facts_smoke.py
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/recharge_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/salesperson_portal_smoke.py
cd web && npm run build && cd ..
python3 -m compileall -q backend
git diff --check
python3 -m alembic -c alembic.ini heads
```

Expected: all exit 0; the head is **unchanged** from Phase 3 — this phase adds no migration.

- [ ] **Step 2: Verify the export file actually opens**

§18 requires 导出文件打开验证. Download one export and open it in Excel or LibreOffice. A file that streams 200 but will not open is a failed gate.

- [ ] **Step 3: Update the handoff to review and commit**

Record commits, commands, results, and risks: export is synchronous and unbounded (large enterprises may time out on Render's free plan); the Outbox worker still needs scheduling; Mini Program was compiled and previewed but not uploaded.

```bash
git add docs/ai-handoffs/timeliness-reporting-phase4.md
git commit -m "docs: mark timeliness reporting phase ready for review"
```
