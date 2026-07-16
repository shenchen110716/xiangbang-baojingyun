# Timeliness Engine Phase (v4.2 Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **⚠ CONTRACT-PROVISIONAL.** This plan was written before Phase 2 merged. Its `Consumes` blocks reference interfaces that Phase 2's plan *promises* but has not yet built. **Before executing Task 1, re-read the merged `backend/services/employment_facts.py` and the merged migration, and reconcile every signature below against what actually shipped.** Where they differ, the merged code wins and this plan must be corrected first. Do not start until `employment-facts-phase2` shows `merged` and has released the migration lock.

**Goal:** Turn authoritative employment facts plus authoritative coverage periods into versioned, auditable timeliness results — enrollment/termination/composite/feedback rates with responsibility attribution — computed by a pure engine and refreshed idempotently through an Outbox.

**Architecture:** A versioned `ProductRuleVersion` snapshot freezes the timing semantics (`billing_mode`, `effective_mode`, last-working-day meaning, minimum coverage, business timezone, feedback grace) at the moment an operation happens, so later product edits never silently rewrite history. A `ParticipationOperation` snapshot records who submitted what and when — attribution survives later reassignment (§8). `backend/services/timeliness_rules.py` is the single date-algorithm home (`normalize_enrollment`, `normalize_termination`, `feedback_deadline`) that Web, reports and the Java mirror must all call rather than reimplement. `backend/services/timeliness_engine.py` is **pure**: it takes plain dataclasses and returns verdicts with zero database or clock access, which is what makes §9/§10's ordered ladders exhaustively testable. `backend/services/timeliness_recalc.py` does the impure work — reads facts and `PolicyMember` coverage, calls the engine, and writes `EmploymentTimelinessResult` rows, superseding rather than mutating.

**Tech Stack:** FastAPI, SQLAlchemy 2, Alembic, Pydantic, SQLite smoke tests.

## Global Constraints

- Base off `main` **after** `employment-facts-phase2` merges. This phase then holds the migration lock; `down_revision` must equal the head Phase 2 left behind — run `python3 -m alembic -c alembic.ini heads` and abort on anything unexpected.
- Do not touch `java-backend/` (Phase 6) or build Web/Mini views (Phase 4). Backend engine + APIs only.
- `PolicyMember` is the sole coverage authority (§8). Read `effective_at`/`terminated_at`; never infer coverage from `InsuredPerson.status` or `policy_id` — the `usage-coverage-authority-hotfix` task already proved that inference wrong.
- 前端、报表和 Java 镜像不得各自复制日期算法 (§8). Every date decision lives in `timeliness_rules.py`.
- 无真实用工事实、未匹配或冲突记录不进入正式指标 (§20.6). Consume only `active_facts(...)`; `unmatched`/`conflict` results are written but excluded from rate denominators.
- 宽限期只影响反馈及时率和责任解释，不改变参保、停保或综合及时率 (§20.3). The grace window must not appear anywhere in the enrollment/termination/composite ladders.
- 提前参保计入及时但单列成本；提前停保不计及时并单列保障缺口 (§20.4). `early` counts in the enrollment numerator; `premature` never counts in the termination numerator.
- 入职和离职分别按业务事件计数，不以员工人数代替事件数 (§20.5).
- 当时没有主要负责人时为 `unassigned_responsibility`，不得归给当前管理员 (§11.3).
- 重算不得重复生成多个当前结果 (§12). Exactly one `status='current'` row per idempotency key, enforced by a partial unique index.

---

### Task 1: Claim the Phase and Establish the Red Engine Contract

**Files:**
- Create: `docs/ai-handoffs/timeliness-engine-phase3.md`
- Create: `tests/timeliness_engine_test.py`

**Interfaces:**
- Produces: the executable §9/§10/§11 acceptance ladder every later task must satisfy.

- [ ] **Step 1: Reconcile against merged Phase 2 (mandatory)**

```bash
bash scripts/ai_coordination_check.sh
python3 -m alembic -c alembic.ini heads
grep -n "def active_facts\|def serialize_fact\|FACT_EXCLUDED_STATUSES" backend/services/employment_facts.py
```

Expected: exactly one head; `active_facts` exists with the signature this plan consumes. If signatures differ, correct this plan before writing code.

- [ ] **Step 2: Create the worktree and handoff**

```bash
git worktree add /private/tmp/xiangbang-timeliness -b feat/timeliness-engine-phase3 main
```

Handoff `docs/ai-handoffs/timeliness-engine-phase3.md`: `status: active`, `migration_owner: yes（Phase 3 独占）`, `depends_on: employment-facts-phase2（已合并）`.

- [ ] **Step 3: Write the failing pure-engine tests — enrollment ladder (§9)**

Create `tests/timeliness_engine_test.py`. The engine is pure, so these need no server and no database:

```python
from backend.services.timeliness_engine import judge_enrollment, EnrollmentInput, Coverage
from datetime import datetime as D

RULE = dict(billing_mode='monthly', effective_mode='next_day', leave_is_last_working_day=True,
            min_coverage_seconds=0, business_timezone='Australia/Melbourne', feedback_grace_seconds=86400)

def test_future_hire_is_pending_and_out_of_denominator():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,5,1), now=D(2026,4,1), coverages=[], rule=RULE))
    assert r.status == 'pending'

def test_coverage_starting_exactly_at_hire_is_timely():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,1), now=D(2026,4,1),
                                         coverages=[Coverage(D(2026,3,1), None)], rule=RULE))
    assert r.status == 'timely' and r.delay_seconds == 0 and r.early_seconds == 0

def test_coverage_starting_before_hire_and_live_at_hire_is_early():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,10), now=D(2026,4,1),
                                         coverages=[Coverage(D(2026,3,1), None)], rule=RULE))
    assert r.status == 'early' and r.early_seconds == 9 * 86400

def test_early_coverage_already_terminated_before_hire_does_not_count_as_live():
    # §9「曾提前生效但在 H 前已经终止的保障，不构成 H 时刻连续有效保障」
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,10), now=D(2026,4,1),
                                         coverages=[Coverage(D(2026,3,1), D(2026,3,5))], rule=RULE))
    assert r.status == 'missing'

def test_first_coverage_after_hire_is_late_with_gap():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,1), now=D(2026,4,1),
                                         coverages=[Coverage(D(2026,3,4), None)], rule=RULE))
    assert r.status == 'late' and r.delay_seconds == 3 * 86400 and r.coverage_gap_seconds == 3 * 86400

def test_hire_reached_with_no_coverage_at_all_is_missing():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,1), now=D(2026,4,1), coverages=[], rule=RULE))
    assert r.status == 'missing'

def test_ambiguous_coverage_candidates_are_conflict_not_guessed():
    r = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,1), now=D(2026,4,1),
                                         coverages=[Coverage(D(2026,3,1), None), Coverage(D(2026,3,1), None)],
                                         rule=RULE))
    assert r.status == 'conflict'
```

- [ ] **Step 4: Write the failing termination ladder tests (§10)**

```python
from backend.services.timeliness_engine import judge_termination, TerminationInput

def test_monthly_last_working_day_expects_next_day_midnight():
    # §10「月保单且离职日期表示最后工作日：S 为离职业务日次日 00:00」
    r = judge_termination(TerminationInput(leave_at=D(2026,3,31,17,0), now=D(2026,4,10),
                                           terminated_at=D(2026,4,1,0,0), rule=RULE))
    assert r.expected_at == D(2026,4,1,0,0) and r.status == 'timely'

def test_daily_product_expects_exact_leave_time_with_no_grace():
    daily = {**RULE, 'billing_mode': 'daily', 'effective_mode': 'immediate',
             'leave_is_last_working_day': False, 'feedback_grace_seconds': 0}
    r = judge_termination(TerminationInput(leave_at=D(2026,3,31,17,0), now=D(2026,4,10),
                                           terminated_at=D(2026,3,31,17,0), rule=daily))
    assert r.expected_at == D(2026,3,31,17,0) and r.status == 'timely'

def test_termination_before_expected_is_premature_and_never_timely():
    r = judge_termination(TerminationInput(leave_at=D(2026,3,31,17,0), now=D(2026,4,10),
                                           terminated_at=D(2026,3,20), rule=RULE))
    assert r.status == 'premature' and r.coverage_gap_seconds > 0

def test_termination_after_expected_is_late_with_excess_period():
    r = judge_termination(TerminationInput(leave_at=D(2026,3,31,17,0), now=D(2026,4,10),
                                           terminated_at=D(2026,4,6), rule=RULE))
    assert r.status == 'late' and r.delay_seconds == 5 * 86400

def test_no_leave_fact_is_pending():
    r = judge_termination(TerminationInput(leave_at=None, now=D(2026,4,10), terminated_at=None, rule=RULE))
    assert r.status == 'pending'

def test_past_expected_with_no_termination_is_missing():
    r = judge_termination(TerminationInput(leave_at=D(2026,3,31,17,0), now=D(2026,4,10),
                                           terminated_at=None, rule=RULE))
    assert r.status == 'missing'
```

- [ ] **Step 5: Write the failing rate and grace tests (§11)**

```python
from backend.services.timeliness_engine import summarise, judge_feedback

def test_enrollment_rate_counts_early_as_timely():
    # §9 参保及时率 = (timely + early) / (timely + early + late + missing)
    s = summarise(enrollment=['timely','early','late','missing','pending','conflict'], termination=[])
    assert s['enrollment_rate'] == 50.0        # (1+1)/4
    assert s['enrollment_due'] == 4            # pending/conflict 不进分母
    assert s['enrollment_timely'] == 2         # timely + early，§13「及时参保」口径

def test_termination_rate_excludes_premature_from_numerator():
    # §10 停保及时率 = timely / (timely + premature + late + missing)
    s = summarise(enrollment=[], termination=['timely','premature','late','missing'])
    assert s['termination_rate'] == 25.0

def test_composite_counts_events_not_people():
    # §11.1 入职和离职各算一个业务事件
    s = summarise(enrollment=['timely','early'], termination=['timely','late'])
    assert s['composite_rate'] == 75.0          # (2+1)/(2+2)

def test_monthly_feedback_within_24h_grace_is_timely():
    assert judge_feedback(event_at=D(2026,3,1,9,0), reported_at=D(2026,3,2,8,0), rule=RULE).status == 'timely'

def test_monthly_feedback_beyond_24h_grace_is_late():
    assert judge_feedback(event_at=D(2026,3,1,9,0), reported_at=D(2026,3,2,10,0), rule=RULE).status == 'late'

def test_daily_product_has_zero_grace():
    daily = {**RULE, 'billing_mode': 'daily', 'feedback_grace_seconds': 0}
    assert judge_feedback(event_at=D(2026,3,1,9,0), reported_at=D(2026,3,1,9,1), rule=daily).status == 'late'

def test_grace_never_changes_the_coverage_verdict():
    # §20.3 宽限只解释反馈责任，不修改保障主及时率
    late_feedback = judge_enrollment(EnrollmentInput(hire_at=D(2026,3,1), now=D(2026,4,1),
                                                     coverages=[Coverage(D(2026,3,4), None)], rule=RULE))
    assert late_feedback.status == 'late'       # 与反馈是否在宽限内无关
```

- [ ] **Step 6: Run and confirm failure**

```bash
python3 tests/timeliness_engine_test.py
```

Expected: FAIL — `backend.services.timeliness_engine` does not exist.

- [ ] **Step 7: Commit the red contract**

```bash
git add tests/timeliness_engine_test.py docs/ai-handoffs/timeliness-engine-phase3.md
git commit -m "test: define timeliness engine ladders and rate contract"
```

---

### Task 2: Versioned Product Rule Service

**Files:**
- Create: `backend/services/timeliness_rules.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/timeliness_rules_test.py`

**Interfaces:**
- Consumes: `InsurancePlan.billing_mode` (`monthly|daily`) and `InsurancePlan.effective_mode` (`next_day|immediate`) — both already exist on `backend/models/plan.py:21-22`.
- Produces — **the only date algorithm in the system** (§8):
  - `rule_snapshot(plan: InsurancePlan) -> dict` — the frozen rule dict: `{billing_mode, effective_mode, leave_is_last_working_day, min_coverage_seconds, business_timezone, feedback_grace_seconds, rule_version}`.
  - `normalize_enrollment(actual_hire_at: datetime, rule: dict) -> datetime`
  - `normalize_termination(actual_leave_at: datetime, rule: dict) -> datetime`
  - `feedback_deadline(event_type: str, actual_business_at: datetime, rule: dict) -> datetime`
  - `RULE_VERSION: int` — bump whenever any algorithm changes; it participates in the recalc idempotency key.

- [ ] **Step 1: Write the failing rule tests**

```python
def test_snapshot_freezes_grace_by_billing_mode():
    assert rule_snapshot(plan(billing_mode='monthly'))['feedback_grace_seconds'] == 86400
    assert rule_snapshot(plan(billing_mode='daily'))['feedback_grace_seconds'] == 0

def test_normalize_termination_monthly_is_next_business_midnight():
    r = rule_snapshot(plan(billing_mode='monthly'))
    assert normalize_termination(D(2026,3,31,17,0), r) == D(2026,4,1,0,0)

def test_normalize_termination_daily_is_exact():
    r = rule_snapshot(plan(billing_mode='daily'))
    assert normalize_termination(D(2026,3,31,17,0), r) == D(2026,3,31,17,0)

def test_normalize_enrollment_next_day_mode():
    r = rule_snapshot(plan(effective_mode='next_day'))
    assert normalize_enrollment(D(2026,3,1,9,0), r) == D(2026,3,2,0,0)

def test_normalize_enrollment_immediate_mode():
    r = rule_snapshot(plan(effective_mode='immediate'))
    assert normalize_enrollment(D(2026,3,1,9,0), r) == D(2026,3,1,9,0)

def test_feedback_deadline_applies_grace_only_for_monthly():
    assert feedback_deadline('enrollment', D(2026,3,1,9,0), rule_snapshot(plan(billing_mode='monthly'))) == D(2026,3,2,9,0)
    assert feedback_deadline('enrollment', D(2026,3,1,9,0), rule_snapshot(plan(billing_mode='daily'))) == D(2026,3,1,9,0)

def test_min_coverage_floor_is_respected():
    r = {**rule_snapshot(plan(billing_mode='daily')), 'min_coverage_seconds': 7 * 86400}
    # 最短保障周期由 normalize_termination 统一处理（§10）
    assert normalize_termination(D(2026,3,3), r, coverage_started_at=D(2026,3,1)) == D(2026,3,8)
```

- [ ] **Step 2: Run and confirm failure**

```bash
python3 tests/timeliness_rules_test.py
```

Expected: FAIL — module not found.

- [ ] **Step 3: Implement the rule service**

Business timezone comes from the snapshot, not the process-global `BUSINESS_TIMEZONE`. **Known gap:** `backend/core/business_time.py:6` reads one process-wide `BUSINESS_TIMEZONE` env var, but §8 makes the timezone part of the versioned rule. Resolve it here by having `rule_snapshot` record `business_timezone` (defaulting to `os.getenv('BUSINESS_TIMEZONE','Australia/Melbourne')` for compatibility) and having every function in this module use the snapshot's value via `ZoneInfo`. Do not change `business_time.py`'s existing global behaviour — other subsystems depend on it.

- [ ] **Step 4: Run and commit**

```bash
python3 tests/timeliness_rules_test.py
git add backend/services/timeliness_rules.py backend/services/__init__.py tests/timeliness_rules_test.py
git commit -m "feat: add versioned product timing rule service"
```

---

### Task 3: Pure Timeliness Engine

**Files:**
- Create: `backend/services/timeliness_engine.py`
- Modify: `backend/services/__init__.py`

**Interfaces:**
- Consumes: `normalize_termination`, `feedback_deadline` (Task 2).
- Produces:
  - `@dataclass Coverage: effective_at: datetime; terminated_at: datetime | None`
  - `@dataclass EnrollmentInput: hire_at; now; coverages: list[Coverage]; rule: dict`
  - `@dataclass TerminationInput: leave_at; now; terminated_at; rule: dict`
  - `@dataclass Verdict: status; expected_at; actual_at; delay_seconds; early_seconds; coverage_gap_seconds`
  - `judge_enrollment(EnrollmentInput) -> Verdict`, `judge_termination(TerminationInput) -> Verdict`, `judge_feedback(event_at, reported_at, rule) -> Verdict`
  - `summarise(enrollment: list[str], termination: list[str]) -> dict` — returns the §13 card vocabulary, which is also the `/api/timeliness/summary` response shape Phase 4 consumes: `enrollment_due`, `enrollment_timely`, `enrollment_late`, `enrollment_missing`, `termination_due`, `termination_timely`, `termination_premature`, `termination_late`, `termination_missing`, `enrollment_rate`, `termination_rate`, `composite_rate`. Phase 4 adds `feedback_rate`, `operator_attributable_rate`, `coverage_gap_seconds` and `excess_premium` on top — those need result rows, not just status lists, so they do not belong in the pure engine.
- **No database, no `datetime.now()` inside this module.** `now` is always injected — that is what makes the ladders deterministic.

- [ ] **Step 1: Implement `judge_enrollment` following §9's order exactly**

The ladder is ordered and must be evaluated top-down: future `H` → `pending`; more than one live candidate → `conflict`; live coverage with `E == H` → `timely`; live coverage with `E < H` → `early`; first coverage with `E > H` → `late`; nothing → `missing`. "Live at `H`" means `effective_at <= H and (terminated_at is None or terminated_at > H)`.

- [ ] **Step 2: Implement `judge_termination` following §10's order exactly**

`S = normalize_termination(leave_at, rule)`. No leave fact or `now < S` → `pending`; `T == S` → `timely`; `T < S` → `premature`; `T > S` → `late`; past `S` with no `T` → `missing`.

- [ ] **Step 3: Implement `summarise`**

```python
ENROLLMENT_NUMERATOR = frozenset({'timely', 'early'})
ENROLLMENT_DENOMINATOR = frozenset({'timely', 'early', 'late', 'missing'})
TERMINATION_NUMERATOR = frozenset({'timely'})
TERMINATION_DENOMINATOR = frozenset({'timely', 'premature', 'late', 'missing'})
```

`pending`, `unmatched` and `conflict` appear in neither set.

- [ ] **Step 4: Run the full engine test suite**

```bash
python3 tests/timeliness_engine_test.py
```

Expected: PASS — all Task 1 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend/services/timeliness_engine.py backend/services/__init__.py
git commit -m "feat: add pure timeliness judgement engine"
```

---

### Task 4: Operation Snapshots and Result Schema

**Files:**
- Create: `backend/migrations_alembic/versions/<rev>_add_timeliness_results.py`
- Create: `backend/models/timeliness.py`
- Modify: `backend/models/__init__.py`, `backend/core/migrations.py`
- Modify: `backend/routers/insured.py`, `backend/routers/enrollment.py` (write snapshots on the five participation-write endpoints)
- Test: `tests/timeliness_model_test.py`

**Interfaces:**
- Produces: `ParticipationOperation`, `EmploymentTimelinessResult`, `TimelinessOutbox` models.

- [ ] **Step 1: Write the failing model test**

```python
def test_only_one_current_result_per_idempotency_key(session):
    add_result(session, fact_id=1, revision_no=1, operation_type='enrollment',
               rule_version=1, calculation_version=1, status='current')
    with raises_integrity():
        add_result(session, fact_id=1, revision_no=1, operation_type='enrollment',
                   rule_version=1, calculation_version=1, status='current')
        session.commit()

def test_superseded_rows_may_repeat_the_key(session):
    # 旧结果标记 superseded 后，同一幂等键可以再有一条 current（§12）
    ...
```

- [ ] **Step 2: Create the migration**

`down_revision` = Phase 2's head. Tables:

```python
    op.create_table(
        "participation_operations",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=True, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True, index=True),
        sa.Column("operation_type", sa.String(20), nullable=False),
        sa.Column("submitted_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("batch_id", sa.Integer, nullable=True),
        sa.Column("plan_id", sa.Integer, sa.ForeignKey("insurance_plans.id"), nullable=True),
        sa.Column("rule_snapshot_json", sa.Text, nullable=False, server_default=""),
        sa.Column("submitted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expected_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("insurer_confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("system_sent_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("operation_type IN ('enrollment','termination')", name="ck_op_type"),
    )

    op.create_table(
        "employment_timeliness_results",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False, index=True),
        sa.Column("employment_fact_revision_no", sa.Integer, nullable=False),
        sa.Column("operation_type", sa.String(20), nullable=False),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=False, index=True),
        sa.Column("actual_employer_id", sa.Integer, sa.ForeignKey("actual_employers.id"), nullable=False, index=True),
        sa.Column("person_id", sa.Integer, sa.ForeignKey("insured_people.id"), nullable=True),
        sa.Column("responsible_user_id", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("primary_manager_user_id", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("actual_business_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("expected_coverage_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("actual_coverage_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("timeliness_status", sa.String(20), nullable=False),
        sa.Column("delay_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("early_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("coverage_gap_seconds", sa.Integer, nullable=False, server_default="0"),
        sa.Column("excess_premium", sa.Float, nullable=False, server_default="0"),
        sa.Column("early_premium", sa.Float, nullable=False, server_default="0"),
        sa.Column("feedback_status", sa.String(20), nullable=False, server_default=""),
        sa.Column("feedback_deadline_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("responsibility_reason", sa.String(40), nullable=False, server_default="normal"),
        sa.Column("responsibility_evidence_json", sa.Text, nullable=False, server_default=""),
        sa.Column("product_rule_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("calculation_version", sa.Integer, nullable=False, server_default="1"),
        sa.Column("calculated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("status", sa.String(20), nullable=False, server_default="current"),
        sa.CheckConstraint(
            "timeliness_status IN ('timely','early','late','missing','premature',"
            "'pending','unmatched','conflict')", name="ck_result_status"),
        sa.CheckConstraint(
            "responsibility_reason IN ('source_feedback_late','operator_processing_late',"
            "'system_processing_late','insurer_confirmation_late','unassigned_responsibility','normal')",
            name="ck_result_reason"),
    )
    # §12 不得重复生成多个当前结果
    op.create_index("ux_result_current", "employment_timeliness_results",
                    ["employment_fact_id", "employment_fact_revision_no", "operation_type",
                     "product_rule_version", "calculation_version"],
                    unique=True,
                    sqlite_where=sa.text("status = 'current'"),
                    postgresql_where=sa.text("status = 'current'"))

    op.create_table(
        "timeliness_outbox",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("employment_fact_id", sa.Integer, sa.ForeignKey("employment_facts.id"), nullable=False),
        sa.Column("reason", sa.String(40), nullable=False, server_default=""),
        sa.Column("status", sa.String(20), nullable=False, server_default="pending"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("last_error", sa.Text, nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("status IN ('pending','processing','done','failed')", name="ck_outbox_status"),
    )
    op.create_index("ux_outbox_live", "timeliness_outbox", ["employment_fact_id"], unique=True,
                    sqlite_where=sa.text("status IN ('pending','processing')"),
                    postgresql_where=sa.text("status IN ('pending','processing')"))
```

- [ ] **Step 3: Write snapshots on the five participation writes**

At each of the five participation-changing endpoints already gated by `require_usage_funded`, insert a `ParticipationOperation` carrying `submitted_by=user.id`, `rule_snapshot_json=json.dumps(rule_snapshot(plan))` and `submitted_at=business_now()`. §8: 即使人员或负责人之后调岗，历史操作归属也不能改变 — the snapshot is written once and never updated.

- [ ] **Step 4: Verify migration up/down and commit**

```bash
TMP=$(mktemp -d); DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini upgrade head
DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini downgrade -1
python3 tests/timeliness_model_test.py
git add backend/models backend/migrations_alembic backend/core/migrations.py backend/routers tests/timeliness_model_test.py
git commit -m "feat: add operation snapshots and timeliness result schema"
```

---

### Task 5: Responsibility Attribution

**Files:**
- Create: `backend/services/timeliness_responsibility.py`
- Test: `tests/timeliness_responsibility_test.py`

**Interfaces:**
- Consumes: `ParticipationOperation` (Task 4); `UserEmployerScope` (Phase 1) for the event-time primary manager.
- Produces: `attribute(session, *, fact, verdict, operation) -> tuple[str, int | None, dict]` returning `(reason, responsible_user_id, evidence)`.

- [ ] **Step 1: Write the failing attribution tests (§11.3)**

```python
def test_successful_operation_is_attributed_to_the_submitter(session):
    reason, uid, _ = attribute(session, fact=f, verdict=timely, operation=op(submitted_by=7))
    assert uid == 7 and reason == 'normal'

def test_batch_row_is_attributed_to_the_confirming_uploader(session):
    reason, uid, _ = attribute(session, fact=f, verdict=late, operation=op(submitted_by=9, batch_id=3))
    assert uid == 9

def test_missing_event_is_attributed_to_the_event_time_primary_manager(session):
    grant_primary(session, user=11, employer=e, assigned_at=D(2026,1,1), revoked_at=D(2026,4,1))
    grant_primary(session, user=12, employer=e, assigned_at=D(2026,4,1), revoked_at=None)
    _, uid, _ = attribute(session, fact=fact(hire=D(2026,3,1)), verdict=missing, operation=None)
    assert uid == 11        # 按事件发生时的主要负责人，而非当前负责人

def test_no_primary_manager_at_event_time_is_unassigned(session):
    reason, uid, _ = attribute(session, fact=fact(hire=D(2026,3,1)), verdict=missing, operation=None)
    assert reason == 'unassigned_responsibility' and uid is None
    # §11.3 不得归给当前管理员

def test_late_feedback_within_monthly_grace_is_not_blamed_on_the_enterprise(session):
    reason, _, _ = attribute(session, fact=fact(reported_at=within_grace), verdict=late, operation=op())
    assert reason != 'source_feedback_late'

def test_evidence_records_the_full_time_chain(session):
    _, _, ev = attribute(session, fact=f, verdict=late, operation=op())
    assert set(ev) >= {'feedback_reported_at', 'submitted_at', 'system_sent_at', 'insurer_confirmed_at'}
```

- [ ] **Step 2: Run, implement, run, commit**

Single main reason for aggregation; the full chain lives in `evidence`. Event-time manager lookup reuses `UserEmployerScope`'s `assigned_at`/`revoked_at` history — that is exactly why Phase 1 stored scopes historically.

```bash
python3 tests/timeliness_responsibility_test.py
git add backend/services/timeliness_responsibility.py tests/timeliness_responsibility_test.py
git commit -m "feat: attribute timeliness responsibility from event-time evidence"
```

---

### Task 6: Recalculation, Outbox and APIs

**Files:**
- Create: `backend/services/timeliness_recalc.py`
- Create: `backend/routers/timeliness.py`
- Modify: `backend/app.py`, `backend/schemas/__init__.py`
- Test: `tests/timeliness_smoke.py`

**Interfaces:**
- Consumes: `active_facts` (Phase 2), engine (Task 3), rules (Task 2), attribution (Task 5).
- Produces the §14.3 contract:

```text
GET  /api/timeliness/summary
GET  /api/timeliness/details
POST /api/timeliness/recalculate
GET  /api/timeliness/data-quality
```

`GET /api/timeliness/export` is **Phase 4** (XLSX export ships with the views).

- [ ] **Step 1: Write the failing recalc tests**

```python
def test_recalc_is_idempotent(ctx):
    recalculate(session, fact_id=f)
    recalculate(session, fact_id=f)
    assert len(current_results(session, f)) == 1     # §12 不得重复生成多个当前结果

def test_fact_correction_supersedes_the_old_result(ctx):
    recalculate(session, fact_id=f)
    new = correct_fact(session, owner, f, actual_hire_at=D(2026,3,5), reason='x')
    recalculate(session, fact_id=new.id)
    assert result_for(session, f).status == 'superseded'
    assert result_for(session, new.id).status == 'current'

def test_unmatched_and_conflict_go_to_data_quality_not_the_rate(ctx):
    assert get_json(owner, '/api/timeliness/summary')['enrollment_due'] == 0
    assert len(get_json(owner, '/api/timeliness/data-quality')['items']) == 1

def test_outbox_retries_and_marks_batch_completed(ctx):
    # Phase 2 把批次留在 imported_pending_calculation，本阶段推进到 completed
    assert batch_status(ctx, batch_id) == 'completed'

def test_project_manager_summary_is_scope_confined(ctx):
    assert get_json(manager, '/api/timeliness/summary')['enrollment_due'] == only_employer_a_events
```

- [ ] **Step 2: Implement the recalculator**

Read the fact, its coverage from `PolicyMember` (authority — §8), its `ParticipationOperation`, and the frozen `rule_snapshot_json`. Call the pure engine. Write the result inside one transaction: mark any existing `current` row for the same key `superseded`, then insert the new `current` row. Advance the owning batch from `imported_pending_calculation` to `completed` once all its facts have current results.

The Outbox worker claims rows with the same conditional-update pattern the `pending_terminations` confirm path already proved (`UPDATE ... WHERE status='pending'`, proceed only when `rowcount == 1`). Retries are bounded; exhausted rows go to `failed` with `last_error` and surface in data-quality.

- [ ] **Step 3: Implement the router**

`POST /api/timeliness/recalculate` is admin/owner only and enqueues Outbox rows rather than computing inline. All reads scope through `allowed_employer_ids`.

- [ ] **Step 4: Run the phase gate**

```bash
python3 tests/timeliness_engine_test.py
python3 tests/timeliness_rules_test.py
python3 tests/timeliness_responsibility_test.py
python3 tests/timeliness_model_test.py
python3 tests/timeliness_smoke.py
python3 tests/employment_facts_smoke.py
python3 tests/employer_scope_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/participation_lock_smoke.py
python3 -m compileall -q backend
git diff --check
python3 -m alembic -c alembic.ini heads
```

Expected: all exit 0; one head.

- [ ] **Step 5: Update the handoff to review and commit**

Record commits, revision, results and risks: rule-version bumps invalidate cached results; the Outbox worker has no scheduler yet (§12 允许 Worker 重试, but this phase runs it on demand — Phase 4 or ops must schedule it); `business_time.py` remains process-global while rules carry their own timezone.

```bash
git add backend/services backend/routers backend/app.py docs/ai-handoffs/timeliness-engine-phase3.md tests/
git commit -m "feat: add timeliness recalculation, outbox and query APIs"
```
