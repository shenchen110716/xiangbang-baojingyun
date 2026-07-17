# 参停保及时率引擎 v4.2 Phase 3

- task_id: `timeliness-engine-phase3`
- owner: `Claude Code`
- status: `active`
- branch: `feat/timeliness-engine-phase3`
- worktree: `/private/tmp/xiangbang-timeliness`
- base_commit: `269357d`
- migration_owner: `yes（Phase 3 独占）`
- depends_on: `employment-facts-phase2（已合并并发布）`
- last_updated: `2026-07-17`

## 目标

把权威用工事实与权威保障期，转换成版本化、可审计的及时率结果——参保/停保/综合/反馈
四类比率与责任归属，由纯函数引擎计算，经 Outbox 幂等刷新。

执行计划：`docs/superpowers/plans/2026-07-17-timeliness-engine-phase.md`（6 个任务）。

## CONTRACT-PROVISIONAL 校准结果（Task 1 Step 1，强制）

计划写于 Phase 2 合并之前，其 `Consumes` 引用的是 Phase 2 计划**承诺**的接口。已对照
**实际合并的代码**逐条核对：

- `active_facts(session, user, *, employer_ids=None, since=None, until=None)` —— 与计划一致。
- `FACT_EXCLUDED_STATUSES = {conflict, pending_match, superseded, voided}` —— 与计划一致。
- `correct_fact`、`serialize_fact` —— 存在。
- Alembic 单一 head `c40dab695a66` —— 即本阶段 `down_revision`。

结论：计划对 Phase 2 的假设成立，无需修正即可执行。

### 校准中发现的计划缺口

`active_facts` 需要 `user` 参数以施加 Phase 1 的数据范围过滤，但**重算是系统级过程，
没有用户上下文**。计划要求「Consume only `active_facts(...)`」，在 recalc 场景下无法直接
满足。实现 Task 6 时需要一个系统级读取路径（不施加 employer scope，但仍必须遵守
§20.6 的状态排除）。届时不得为图省事绕开状态排除。

## Active Phase 3 Scope

- `ProductRuleVersion` 规则版本快照与 `timeliness_rules.py` 日期算法唯一实现
- `timeliness_engine.py` 纯函数引擎（§9/§10/§11 的有序阶梯）
- `ParticipationOperation` 操作快照与 `EmploymentTimelinessResult` 结果表
- 责任归属（含 `unassigned_responsibility`）
- `timeliness_recalc.py`、Outbox 与及时率 API
- 新 Alembic 迁移（接在 `c40dab695a66` 之后）

## 明确不做

- Java 镜像（Phase 6）、Web 与小程序界面（Phase 4）。
- 佣金结算（Phase 5）。

## 验证

待 Task 6 阶段门槛填写。

## 风险与阻塞

- **PostgreSQL 验证门槛尚未就绪**：`scripts/pg_migration_check.py` 已入库但因缺少 Neon
  凭据未端到端跑通。本阶段将新建迁移，而 Phase 2 正是在此处翻车（SQLite 通过、
  PostgreSQL 拒绝布尔列的整数默认值）。合并前应设法在真实 PostgreSQL 上执行本阶段迁移，
  否则同类缺陷会再次到部署时才暴露。
