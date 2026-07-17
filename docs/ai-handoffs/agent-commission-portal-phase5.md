# 业务员产品与佣金门户 v4.2 Phase 5

- task_id: `agent-commission-portal-phase5`
- owner: `Claude Code`
- status: `active`
- branch: `feat/agent-commission-portal-phase5`
- worktree: `/private/tmp/xiangbang-agent-portal`
- base_commit: `ad35576`
- migration_owner: `yes（Phase 5 独占）`
- depends_on: `salesperson-portal（已合并 b664e20）`
- last_updated: `2026-07-17`

## 目标

业务员可查看全部在售产品与平台最低销售价，以及**仅本人**的佣金指标、结算单与付款记录；
结算/付款/分配账本只追加，已确认金额不得原地改写。

执行计划：`docs/superpowers/plans/2026-07-17-agent-commission-portal-phase.md`（6 个任务）。

## 校准结果（Task 1 Step 1）

- 迁移锁空闲；head `7f0a1fa05267`，即本阶段 `down_revision`。
- 计划假设的既有接口全部属实：`plan_price_for_class`、`strip_internal_pricing`
  （`backend/services/pricing.py`）、`commission_accrual`、`agent_commission_rows`、
  `agent_commission_summary`（`backend/services/commissions.py`）。
- `InsurancePlan` 具备白名单所需的 `insurer`、`name`、`coverage`、`occupation_classes`、
  `billing_mode`、`effective_mode`、`status`。

## §5.1 已在生产被违反过，本阶段必须守住

`strip_internal_pricing` 曾只对 `enterprise` 角色脱敏，业务员可见保司结算底价与平台利润。
已由 `fix/agent-pricing-leak`（`0caa07b`）修复并发布：脱敏按角色查表，业务员保留
`minimum_sale_price` 但看不到成本构成。**那是减法式黑名单，属临时止血**；本阶段仍欠
`/api/agent-portal/products` 的**白名单 schema**——多一个字段就算泄漏，且新增产品列时
不会自动泄露。回归测试在 `tests/agent_pricing_visibility_test.py`，应扩展而非替换。

## Active Phase 5 Scope

- `AgentCommissionStatement` / `StatementItem` / `Payment` / `PaymentAllocation` 迁移与模型
- `backend/services/agent_settlement.py` 结算服务（追加式账本）
- `backend/routers/agent_portal.py` §14.4 契约
- Web 业务员门户页面

## 明确不做

- Java 镜像（Phase 6）、及时率相关（Phase 3/4 已完成）。

## 验证

待 Task 6 阶段门槛填写。

## 风险与阻塞

- **本阶段新建迁移，但 PostgreSQL 门槛未启用**（缺凭据）。用户已明确接受"迁移靠部署验证"。
  Phase 2 的教训：SQLite 通过与离线 SQL 生成都不足以证明 PostgreSQL 可用。
  **本阶段将建含金额、状态与布尔标志的四张表，正是同类缺陷高发区**；
  布尔列默认值一律用 `sa.true()`/`sa.false()`，不得用 `text("1")`。
