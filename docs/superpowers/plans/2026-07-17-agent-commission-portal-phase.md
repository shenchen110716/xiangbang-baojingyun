# Agent Commission Portal Phase (v4.2 Phase 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a salesperson see every sellable product with the platform's minimum sale price, plus their **own** commission metrics, statements and payments — backed by a settlement/payment/allocation ledger that never rewrites confirmed money.

**Architecture:** Two hard separations drive the design. First, product reads and own-commission reads are different endpoints with different response schemas (§5.1) — internal cost, platform profit and other agents' commissions are excluded by an allow-list schema on the server, never hidden in the browser. Second, the money model is append-only: `AgentCommissionStatement` freezes a period's total, `AgentCommissionStatementItem` freezes each source's amount snapshot, `AgentCommissionPayment` records what the platform actually paid, and `AgentCommissionPaymentAllocation` maps payments to statements many-to-many so one statement can be paid in instalments and one payment can clear several statements. Corrections are adjustment or reversal rows, never in-place edits (§5.3). List, summary and export all call one filter service (§14.4) so the three can never disagree.

**Tech Stack:** FastAPI, SQLAlchemy 2, Alembic, Pydantic, openpyxl, Vue 3 + Element Plus.

## Dependency and scheduling note

The roadmap gates this phase only on the `salesperson-portal` work, which **is merged** (`b664e20`, verified in `main`) with no surviving branch — so its interfaces are real and this plan is grounded, not speculative.

**However, this phase creates a migration, and the Alembic lock is serial.** It cannot hold the lock while Phase 2 or Phase 3 holds it. Either run it after Phase 3 merges, or run it in parallel *only* up to the point of creating the migration and pause there, recording the block in the handoff. Do not branch a second migration head — `CLAUDE.md` makes a single head mandatory.

## Global Constraints

- Base off the latest `main`. Run `python3 -m alembic -c alembic.ini heads` and use the sole head as `down_revision`. Abort if another task holds the lock.
- Do not touch `java-backend/` (Phase 6) or the timeliness subsystem.
- **Reuse, do not duplicate.** `backend/services/commissions.py` already has `commission_accrual`, `agent_commission_rows`, `agent_commission_summary`; `backend/services/pricing.py` already has `plan_price_for_class` and `strip_internal_pricing`. §19: 不能建立第二套业务员接口或导航. Extend these; do not fork them.
- `/agent-portal` already exists in `_FRONTEND_ROUTES` (`backend/app.py:99`) — the SPA route is whitelisted, but **no `/api/agent-portal/*` endpoint exists yet**. This phase adds them.
- 业务员不能通过传入 `agent_id` 查询他人数据 (§17.1). Every portal endpoint derives the agent from the JWT and must ignore or reject a body/query `agent_id`.
- 禁止返回保险原价、保司结算底价、平台利润、总返佣金额、其他业务员佣金、企业经营数据 (§5.1). Enforced by response schema, asserted by test.
- **§5.1 was already breached in production and hotfixed on `main@0caa07b` — read that diff before Task 4.** `strip_internal_pricing` masked only the `enterprise` role, so a salesperson calling `GET /api/plans` received `insurer_settlement_price`, `insurance_base_price`, `profit_amount`, `total_commission_*` and `platform_margin_amount`; `/api/dashboard` and `/api/screen/products` returned every enterprise's operating data. The hotfix keys the hidden set by role (salespeople keep `minimum_sale_price`, which the portal quotes from) and gates the two dashboard endpoints to `admin`/`enterprise`. That fix is **subtractive (deny-list)** and interim: this phase still owes the §5.1 **allow-list** schema on `/api/agent-portal/products`. Regression coverage lives in `tests/agent_pricing_visibility_test.py` — extend it, do not replace it.
- 已确认结算项不得原地改写 (§5.3). Confirmed items are immutable; corrections are new adjustment rows.
- 分配金额不得超过付款可分配余额或结算单未付余额 (§5.3). Enforced in one transaction with a DB-expressible guard.
- 列表与导出必须调用同一查询服务 (§14.4).

---

### Task 1: Claim the Phase and Establish the Red Leakage Contract

**Files:**
- Create: `docs/ai-handoffs/agent-commission-portal-phase5.md`
- Create: `tests/agent_portal_smoke.py`

**Interfaces:**
- Consumes: existing salesperson login and `/api/agents/me`.
- Produces: the executable §5.1/§17.1 leakage contract.

- [ ] **Step 1: Preflight and confirm the lock is free**

```bash
bash scripts/ai_coordination_check.sh
python3 -m alembic -c alembic.ini heads
grep -rn "def strip_internal_pricing\|def plan_price_for_class" backend/services/pricing.py
grep -rn "def agent_commission_summary\|def agent_commission_rows" backend/services/commissions.py
```

Expected: one head, no active migration owner. If Phase 2/3 holds it, stop and record the block.

- [ ] **Step 2: Create the worktree and handoff**

```bash
git worktree add /private/tmp/xiangbang-agent-portal -b feat/agent-commission-portal-phase5 main
```

Handoff: `status: active`, `migration_owner: yes（Phase 5 独占）`, `depends_on: salesperson-portal（已合并 b664e20）`.

- [ ] **Step 3: Write the failing leakage and isolation tests**

```python
FORBIDDEN = ['price', 'cost_price', 'settle_price', 'profit_amount', 'commission_rate',
             'markup_amount', 'total_rebate']

def test_product_list_returns_all_sellable_products_even_without_a_relation(ctx):
    # §5.1 即使产品尚未为该业务员配置佣金关系也要返回
    make_plan(ctx, name='未配置佣金的产品')
    names = [p['name'] for p in get_json(agent, '/api/agent-portal/products')['items']]
    assert '未配置佣金的产品' in names

def test_product_response_exposes_min_price_but_no_internal_cost(ctx):
    p = get_json(agent, '/api/agent-portal/products')['items'][0]
    assert set(p) == {'id', 'insurer', 'name', 'coverage', 'occupation_classes',
                      'billing_mode', 'effective_mode', 'status',
                      'min_sale_price', 'my_commission_status'}   # 白名单，多一个字段就算泄漏
    for key in FORBIDDEN:
        assert key not in p

def test_unconfigured_product_shows_not_configured(ctx):
    p = product_named(ctx, '未配置佣金的产品')
    assert p['my_commission_status'] == '未配置'

def test_agent_cannot_read_another_agents_commissions_via_agent_id(ctx):
    # §17.1 不能通过传入 agent_id 查询他人数据
    mine = get_json(agent_a, '/api/agent-portal/commissions/summary')
    spoof = get_json(agent_a, f'/api/agent-portal/commissions/summary?agent_id={agent_b.id}')
    assert spoof == mine                       # agent_id 被忽略，而非生效

def test_agent_cannot_reach_enterprise_or_admin_surfaces(ctx):
    assert get(agent, '/api/insured').status == 403
    assert get(agent, '/api/agent-commissions').status == 403
    assert get(agent, '/api/timeliness/summary').status == 403

def test_summary_and_details_and_export_agree(ctx):
    # §14.4 列表与导出必须调用同一查询服务
    s = get_json(agent, '/api/agent-portal/commissions/summary?enterprise_id=1')
    rows = get_json(agent, '/api/agent-portal/commissions/details?enterprise_id=1')['items']
    book = load_xlsx(get(agent, '/api/agent-portal/commissions/export?enterprise_id=1').content)
    assert round(sum(r['amount'] for r in rows), 2) == round(s['estimated_total'], 2)
    assert book.active.max_row - 1 == len(rows)
```

- [ ] **Step 4: Run and confirm failure, then commit**

```bash
python3 tests/agent_portal_smoke.py       # FAIL: /api/agent-portal/products → 404
git add tests/agent_portal_smoke.py docs/ai-handoffs/agent-commission-portal-phase5.md
git commit -m "test: define agent portal isolation and pricing leakage contract"
```

---

### Task 2: Settlement Ledger Schema

**Files:**
- Create: `backend/migrations_alembic/versions/<rev>_add_agent_settlements.py`
- Modify: `backend/models/finance.py`, `backend/models/__init__.py`, `backend/core/migrations.py`
- Test: `tests/agent_settlement_model_test.py`

**Interfaces:**
- Consumes: existing `AgentCommission` (`backend/models/finance.py:11`) and `PaymentRecord` (`:25`) — do not modify their columns; this phase adds alongside.
- Produces: `AgentCommissionStatement`, `AgentCommissionStatementItem`, `AgentCommissionPayment`, `AgentCommissionPaymentAllocation`.

- [ ] **Step 1: Write the failing model tests**

```python
def test_allocation_cannot_exceed_payment_balance(session):
    pay = make_payment(session, amount=100)
    make_allocation(session, payment=pay, statement=s1, amount=80)
    with raises_http(400):
        allocate(session, payment=pay, statement=s2, amount=30)   # 只剩 20

def test_allocation_cannot_exceed_statement_unpaid_balance(session):
    st = make_statement(session, total=50)
    with raises_http(400):
        allocate(session, payment=make_payment(session, amount=100), statement=st, amount=60)

def test_one_statement_can_be_paid_in_instalments(session):
    st = make_statement(session, total=100)
    allocate(session, payment=make_payment(session, amount=60), statement=st, amount=60)
    allocate(session, payment=make_payment(session, amount=40), statement=st, amount=40)
    assert statement_paid(session, st) == 100 and st.status == 'paid'

def test_one_payment_can_clear_several_statements(session):
    pay = make_payment(session, amount=100)
    allocate(session, payment=pay, statement=make_statement(session, total=60), amount=60)
    allocate(session, payment=pay, statement=make_statement(session, total=40), amount=40)
    assert payment_allocated(session, pay) == 100

def test_confirmed_item_cannot_be_rewritten_in_place(session):
    item = make_item(session, amount=100, status='confirmed')
    with raises_http(409):
        update_item_amount(session, item, 120)     # §5.3 差错只能用调整项或冲正
```

- [ ] **Step 2: Create the migration**

```python
    op.create_table(
        "agent_commission_statements",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("agent_id", sa.Integer, sa.ForeignKey("users.id"), nullable=False, index=True),
        sa.Column("statement_no", sa.String(40), nullable=False, unique=True),
        sa.Column("period_start", sa.Date, nullable=False),
        sa.Column("period_end", sa.Date, nullable=False),
        sa.Column("currency", sa.String(8), nullable=False, server_default="CNY"),
        sa.Column("total_amount", sa.Float, nullable=False, server_default="0"),
        sa.Column("status", sa.String(20), nullable=False, server_default="draft"),
        sa.Column("confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("period_end >= period_start", name="ck_statement_period"),
        sa.CheckConstraint("status IN ('draft','confirmed','partially_paid','paid','void')",
                           name="ck_statement_status"),
    )
    op.create_table(
        "agent_commission_statement_items",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("statement_id", sa.Integer, sa.ForeignKey("agent_commission_statements.id"),
                  nullable=False, index=True),
        sa.Column("source_type", sa.String(20), nullable=False, server_default="accrual"),
        sa.Column("policy_member_id", sa.Integer, sa.ForeignKey("policy_members.id"), nullable=True),
        sa.Column("plan_id", sa.Integer, sa.ForeignKey("insurance_plans.id"), nullable=True),
        sa.Column("enterprise_id", sa.Integer, sa.ForeignKey("enterprises.id"), nullable=True),
        sa.Column("period_start", sa.Date, nullable=True),
        sa.Column("period_end", sa.Date, nullable=True),
        sa.Column("amount", sa.Float, nullable=False, server_default="0"),
        sa.Column("amount_snapshot_json", sa.Text, nullable=False, server_default=""),
        sa.Column("status", sa.String(20), nullable=False, server_default="draft"),
        sa.Column("adjusts_item_id", sa.Integer, sa.ForeignKey("agent_commission_statement_items.id"),
                  nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("source_type IN ('accrual','adjustment','reversal')", name="ck_item_source"),
        sa.CheckConstraint("status IN ('draft','confirmed','void')", name="ck_item_status"),
    )
    op.create_table(
        "agent_commission_payments",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("agent_id", sa.Integer, sa.ForeignKey("users.id"), nullable=False, index=True),
        sa.Column("amount", sa.Float, nullable=False, server_default="0"),
        sa.Column("channel", sa.String(30), nullable=False, server_default=""),
        sa.Column("transaction_no", sa.String(80), nullable=False, server_default=""),
        sa.Column("paid_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("voucher_url", sa.Text, nullable=False, server_default=""),
        sa.Column("created_by", sa.Integer, sa.ForeignKey("users.id"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("amount > 0", name="ck_payment_amount"),
    )
    op.create_index("ux_payment_txn", "agent_commission_payments", ["channel", "transaction_no"],
                    unique=True,
                    sqlite_where=sa.text("transaction_no != ''"),
                    postgresql_where=sa.text("transaction_no != ''"))
    op.create_table(
        "agent_commission_payment_allocations",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("payment_id", sa.Integer, sa.ForeignKey("agent_commission_payments.id"),
                  nullable=False, index=True),
        sa.Column("statement_id", sa.Integer, sa.ForeignKey("agent_commission_statements.id"),
                  nullable=False, index=True),
        sa.Column("amount", sa.Float, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("amount > 0", name="ck_allocation_amount"),
    )
    op.create_index("ux_allocation_pair", "agent_commission_payment_allocations",
                    ["payment_id", "statement_id"], unique=True)
```

The balance ceilings (§5.3) cannot be expressed as a single CHECK across tables — enforce them in the service inside one transaction, with `SELECT ... FOR UPDATE`-equivalent row locking on the payment and statement rows, and assert with the Step 1 tests.

- [ ] **Step 3: Add the models and SQLite bridge, verify up/down, commit**

```bash
TMP=$(mktemp -d); DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini upgrade head
DATABASE_URL="sqlite:///$TMP/t.db" python3 -m alembic -c alembic.ini downgrade -1
python3 tests/agent_settlement_model_test.py
git add backend/models backend/migrations_alembic backend/core/migrations.py tests/agent_settlement_model_test.py
git commit -m "feat: add agent commission settlement and payment schema"
```

---

### Task 3: Settlement Service

**Files:**
- Create: `backend/services/agent_settlements.py`
- Modify: `backend/services/__init__.py`
- Test: `tests/agent_settlement_service_test.py`

**Interfaces:**
- Consumes: `commission_accrual` (`backend/services/commissions.py:14`) for amount snapshots.
- Produces:
  - `build_statement(session, admin, *, agent_id, period_start, period_end) -> AgentCommissionStatement` (draft)
  - `confirm_statement(session, admin, statement_id) -> AgentCommissionStatement` (freezes items)
  - `adjust_item(session, admin, item_id, *, amount, reason) -> AgentCommissionStatementItem` (new adjustment row)
  - `record_payment(session, admin, *, agent_id, amount, channel, transaction_no, paid_at, voucher_url) -> AgentCommissionPayment`
  - `allocate(session, admin, *, payment_id, statement_id, amount) -> AgentCommissionPaymentAllocation`
  - `agent_balances(session, agent_id) -> dict` → `{estimated_total, pending_settlement, pending_payment, paid}` (§5.2)

- [ ] **Step 1: Write the failing service tests**

```python
def test_confirm_freezes_the_amount_snapshot(session):
    st = build_statement(session, admin, agent_id=7, period_start=d1, period_end=d2)
    confirm_statement(session, admin, st.id)
    change_underlying_commission_rate(session, agent_id=7, rate=0.99)
    assert item_amount(session, st) == frozen_amount      # 结算项固化金额快照（§5.3）

def test_adjustment_creates_a_new_row_pointing_at_the_original(session):
    item = confirmed_item(session, amount=100)
    adj = adjust_item(session, admin, item.id, amount=-20, reason='多算')
    assert adj.id != item.id and adj.adjusts_item_id == item.id and adj.source_type == 'adjustment'
    assert item.amount == 100                              # 原项不变

def test_balances_split_the_four_states(session):
    # §5.2 预估累计 / 待结算 / 待支付 / 已支付
    b = agent_balances(session, agent_id=7)
    assert set(b) == {'estimated_total', 'pending_settlement', 'pending_payment', 'paid'}

def test_paid_balance_counts_allocations_not_payments(session):
    # 一次付款覆盖多张结算单时，已支付按分配额计
    pay = record_payment(session, admin, agent_id=7, amount=100, ...)
    allocate(session, admin, payment_id=pay.id, statement_id=st.id, amount=60)
    assert agent_balances(session, 7)['paid'] == 60         # 而非 100

def test_statement_status_follows_allocation_progress(session):
    st = confirmed_statement(session, total=100)
    allocate(session, admin, payment_id=p1, statement_id=st.id, amount=60)
    assert st.status == 'partially_paid'
    allocate(session, admin, payment_id=p2, statement_id=st.id, amount=40)
    assert st.status == 'paid'
```

- [ ] **Step 2: Run, implement, run, commit**

```bash
python3 tests/agent_settlement_service_test.py
git add backend/services/agent_settlements.py backend/services/__init__.py tests/agent_settlement_service_test.py
git commit -m "feat: add agent settlement, payment and allocation service"
```

---

### Task 4: Agent Portal APIs

**Files:**
- Create: `backend/routers/agent_portal.py`
- Create: `backend/schemas/agent_portal.py`
- Modify: `backend/app.py`, `backend/routers/__init__.py`, `backend/schemas/__init__.py`
- Create: `backend/services/agent_portal_query.py`

**Interfaces:**
- Consumes: Task 3 service; `plan_price_for_class`, `strip_internal_pricing` (`backend/services/pricing.py`); `agent_commission_rows` (`backend/services/commissions.py:58`).
- Produces the §14.4 contract:

```text
GET /api/agent-portal/products
GET /api/agent-portal/commissions/summary
GET /api/agent-portal/commissions/details
GET /api/agent-portal/commissions/export
GET /api/agent-portal/statements
GET /api/agent-portal/statements/{id}
GET /api/agent-portal/payments
```

- [ ] **Step 1: Add the allow-list schemas**

Create `backend/schemas/agent_portal.py`. `AgentProductOut` declares **exactly** the ten fields Task 1 asserts and nothing else — §5.1 requires 响应 Schema 使用字段白名单，不能仅在前端隐藏内部字段. Do not build this by deleting keys from `plan_dict`; construct it field by field so a new internal column can never leak by default.

- [ ] **Step 2: Add the shared query service**

`backend/services/agent_portal_query.py` exposes `commission_rows(session, agent_id, filters)` and `commission_summary(session, agent_id, filters)` over one `CommissionFilters` dataclass (time, enterprise, insurer, product). The list, summary and export endpoints all call it — §14.4 forbids three divergent queries.

- [ ] **Step 3: Implement the router**

Every endpoint carries `dependencies=[Depends(require_role("salesperson", ...))]` following the `backend/routers/agents.py:40` idiom, and derives `agent_id = user.id` from the JWT. A supplied `agent_id` parameter is ignored — never honoured (§17.1). `min_sale_price` comes from `plan_price_for_class`; it is computed server-side and only displayed by the client (§5.1).

- [ ] **Step 4: Register the router and run**

`/agent-portal` is already whitelisted in `_FRONTEND_ROUTES`, so no `backend/app.py` route change is needed — only the `include_router` call.

```bash
python3 tests/agent_portal_smoke.py
python3 tests/security_smoke.py
python3 tests/salesperson_portal_smoke.py     # 既有门户不得回归
git add backend/routers backend/schemas backend/services backend/app.py
git commit -m "feat: add agent portal product and commission APIs"
```

---

### Task 5: Agent Portal Web Views

**Files:**
- Modify: `web/src/views/agent/AgentPortalView.vue` (or create if the merged portal put it elsewhere — check first)
- Create: `web/src/api/agentPortal.ts`
- Modify: `web/src/api/types.ts`

- [ ] **Step 1: Find the merged portal view before creating anything**

```bash
grep -rln "agent-portal\|AgentPortal" web/src/
```

§19: 不能建立第二套业务员接口或导航. Extend the view that `b664e20` merged; only create a new one if none exists.

- [ ] **Step 2: Add types and API calls**

```ts
export interface AgentProduct {
  id: number; insurer: string; name: string; coverage: string
  occupation_classes: string; billing_mode: 'monthly' | 'daily'
  effective_mode: 'next_day' | 'immediate'; status: string
  min_sale_price: number; my_commission_status: string
}

export interface AgentCommissionBalances {
  estimated_total: number; pending_settlement: number
  pending_payment: number; paid: number
}
```

- [ ] **Step 3: Build the product centre and commission tabs**

Product tab: all sellable products with `min_sale_price` and `my_commission_status` (`未配置` when absent). Commission tab: the four §5.2 balance cards, a filtered detail table (time/enterprise/insurer/product), an export button, plus statement and payment lists.

- [ ] **Step 4: Build and commit**

```bash
cd web && npx vue-tsc -b --noEmit && npm run build && cd ..
python3 tests/agent_portal_smoke.py
git add web/src
git commit -m "feat: add agent product centre and commission portal views"
```

---

### Task 6: Phase Gate and Handoff

- [ ] **Step 1: Run the full matrix**

```bash
python3 tests/agent_settlement_model_test.py
python3 tests/agent_settlement_service_test.py
python3 tests/agent_portal_smoke.py
python3 tests/salesperson_portal_smoke.py
python3 tests/security_smoke.py
python3 tests/system_smoke.py
python3 tests/recharge_smoke.py
python3 tests/participation_lock_smoke.py
python3 tests/employer_scope_smoke.py
cd web && npm run build && cd ..
python3 -m compileall -q backend
git diff --check
python3 -m alembic -c alembic.ini heads
```

Expected: all exit 0; exactly one head.

- [ ] **Step 2: Verify the leakage contract by hand**

Log in as a salesperson and read `/api/agent-portal/products` raw. Confirm with your own eyes that no cost, profit, rebate or other-agent field appears. §5.1 is the requirement most likely to regress silently as columns get added to `InsurancePlan`, and a schema allow-list only helps if it is actually an allow-list.

- [ ] **Step 3: Update the handoff to review and commit**

Record commits, revision, commands, results and risks: the settlement builder is manual (no scheduled period close); allocation guards rely on transactional row locks, verified on SQLite but not on a real two-connection PostgreSQL setup; voucher upload reuses the signed-download pattern and is never statically mounted.

```bash
git add docs/ai-handoffs/agent-commission-portal-phase5.md
git commit -m "docs: mark agent commission portal phase ready for review"
```
