# 参停保及时率引擎 v4.2 Phase 3

- task_id: `timeliness-engine-phase3`
- owner: `Claude Code`
- status: `merged`（已合并、已推送、已发布生产）
- branch: `feat/timeliness-engine-phase3`
- worktree: `/private/tmp/xiangbang-timeliness`
- base_commit: `269357d`
- migration_owner: `no（已释放；迁移 7f0a1fa05267 已在生产 PostgreSQL 执行）`
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

## 提交

- `9b7953e` — 红灯引擎契约 + CONTRACT-PROVISIONAL 校准
- `67be881` — 版本化产品规则服务（唯一日期算法）
- `ee57368` — 纯函数及时率引擎
- `22d3ba1` — 操作快照、结果表与 Outbox 迁移 `7f0a1fa05267`
- `f4e57cc` — 责任归属（事件时点负责人）
- 本次 — 重算、Outbox、五个写入点快照与 §14.3 API

## 验证（2026-07-17，均在最终提交状态上执行）

- `[x]` `timeliness_engine_test`（21）、`timeliness_rules_test`（11）、
  `timeliness_responsibility_test`（6）、`timeliness_model_test`（7）、`timeliness_smoke`（7）
- `[x]` Phase 2 回归：`employment_facts_smoke`、`employment_import_test`、
  `employment_matching_test`、`employment_integration_test`、`id_number_test`
- `[x]` 既有回归：`employer_scope_smoke`、`security_smoke`、`system_smoke`、
  `participation_lock_smoke`、`recharge_smoke`、`salesperson_portal_smoke`、
  `agent_pricing_visibility_test`
- `[x]` `compileall`、`git diff --check`、单一 head `7f0a1fa05267`
- `[x]` 迁移在线双向：SQLite 上 downgrade 干净删表、upgrade 重建表与索引
- `[x]` 引擎纯度自检：模块内无 `now()`、无 `session`、无 `select`
- `[ ]` **真实 PostgreSQL 未执行本阶段迁移**：门槛脚本已入库但缺凭据（见阻塞）。
- `[ ]` Web 构建与 Maven：本阶段未改前端与 Java，按计划不要求。

## 合并

- 合并提交：`59810c5`，无冲突；合并后在 `main` 上重跑 14 项测试、单一 head `7f0a1fa05267`、
  `compileall` 全部通过。
- 已推送 `fdd3228..e6b6e96` 并经 Render 自动部署，`e6b6e96` 状态 `live`。
- 生产验证：`/api/health` 200；路由 103 → **107**，四条及时率路由就位；
  `/api/timeliness/summary` 与 `/data-quality` 未带 token 返回 401。容器启动命令为
  `alembic upgrade head && uvicorn`，服务正常即证明 `7f0a1fa05267` 已在生产 PostgreSQL 执行。
- **本次迁移未经合并前的真实 PostgreSQL 验证**（凭据未提供），实际是靠部署本身作为验证。
  本次通过属于结果良好，不代表流程到位；`CLAUDE.md` 已要求新迁移合并前必须过
  `scripts/pg_migration_check.py`，该脚本仍待凭据启用。

## 已知风险

- `RULE_VERSION`/`CALCULATION_VERSION` 提升会使既有结果的幂等键变化，旧结果不再是
  当前版本；这是设计意图（可重算而非静默过期），但升级时需要一次全量重算。
- **Outbox 没有调度器**：本阶段只提供按需处理（`POST /api/timeliness/recalculate`）。
  §12 允许 Worker 重试，但定时调度需由 Phase 4 或运维接入，否则导入后的结果只在
  有人手动触发时才刷新。
- `business_time.py` 仍是进程级全局时区，而规则快照自带时区（见"跨阶段隐患"）。
- 责任归属的 `_late_reason` 只在有操作记录时能分辨环节；历史数据没有
  `ParticipationOperation`，会落到事件时点负责人或 `unassigned_responsibility`。

## 实施中发现的跨阶段隐患

`UserEmployerScope.assigned_at/revoked_at`（Phase 1 及更早的全部 9 个模型文件）声明为
**无时区** `DateTime`，存 UTC 裸值；而 Phase 2/3 的时间列声明为 `DateTime(timezone=True)`。
两者直接比较会抛 `can't compare offset-naive and offset-aware datetimes`，在 SQL 条件里
更糟——不会报错，而是比较两种不同表示，静默出错。

本阶段在 `timeliness_responsibility._scope_frame()` 做显式桥接并保留注释标明接缝，
**未改写历史模型**（改遍 9 个文件风险大且超出本阶段范围）。这是全仓范围的隐患，
建议单独立项统一，否则每个跨 Phase 1/2 边界的新查询都要重新踩一次。

## 风险与阻塞

- **PostgreSQL 验证门槛尚未就绪**：`scripts/pg_migration_check.py` 已入库但因缺少 Neon
  凭据未端到端跑通。本阶段将新建迁移，而 Phase 2 正是在此处翻车（SQLite 通过、
  PostgreSQL 拒绝布尔列的整数默认值）。合并前应设法在真实 PostgreSQL 上执行本阶段迁移，
  否则同类缺陷会再次到部署时才暴露。
