# 及时率报表、数据质量与导出 v4.2 Phase 4

- task_id: `timeliness-reporting-phase4`
- owner: `Claude Code`
- status: `active`
- branch: `feat/timeliness-reporting-phase4`
- worktree: `/private/tmp/xiangbang-timeliness-report`
- base_commit: `81b6eeb`
- migration_owner: `no（本阶段不建迁移）`
- depends_on: `timeliness-engine-phase3（已合并并发布生产）`
- last_updated: `2026-07-17`

## 目标

把 Phase 3 的结果变成人能据以行动的东西：企业主管、项目负责人与平台管理员的统计卡片、
筛选明细、数据质量队列和带审计的 XLSX 导出。

执行计划：`docs/superpowers/plans/2026-07-17-timeliness-reporting-phase.md`（6 个任务）。

## CONTRACT-PROVISIONAL 校准结果（Task 1 Step 1，强制）

计划写于 Phase 2–3 合并之前，其 `Consumes` 引用的是那两份计划**承诺**的结果 schema 与
summary API。已对照**实际合并并发布**的代码逐条核对：

- `summarise(*, enrollment: list, termination: list) -> dict` 返回 12 个字段，与计划
  Task 1 断言的口径一致（`enrollment_due/timely/late/missing`、
  `termination_due/timely/premature/late/missing`、三个 rate）。
- 及时率状态词表为 `timely|early|late|missing|premature|pending|unmatched|conflict`，
  与计划一致；责任原因词表与 §11.3 一致。
- Alembic head `7f0a1fa05267`，**本阶段不得改变它**。

结论：计划对 Phase 3 的假设成立，无需修正即可执行。

### 校准中发现并已修复的缺口

Task 1 的断言「项目负责人访问 `/api/employment-feedback/batches` 应 403」暴露了 Phase 2
的真实越权：该端点原挂 `require_role("admin","enterprise")`，而项目负责人的 role 正是
`enterprise`。探针实测：零授权单位的项目负责人可读取全企业导入批次。已由
`fix/batch-owner-only`（`e6b6e96`）修复并发布生产。

## Active Phase 4 Scope

- `backend/services/timeliness_reporting.py` 聚合服务（§13 统计卡片的四个补充指标）
- `GET /api/timeliness/export` 带审计的 XLSX 导出
- Web：企业主管及时率总览、明细筛选、数据质量队列
- 小程序：项目负责人子集
- `_FRONTEND_ROUTES` 白名单同步

## 明确不做

- 新建迁移（若发现需要，说明 Phase 3 的 schema 错了，应回到那里修）。
- Java 镜像（Phase 6）、佣金门户（Phase 5）。

## 验证

待 Task 6 阶段门槛填写。

## 风险与阻塞

- **PostgreSQL 门槛仍未启用**：`scripts/pg_migration_check.py` 缺凭据。本阶段不建迁移，
  不受影响；但 Phase 5 将新建结算/付款表，届时应先就绪。
- 新 Web 路由必须同步加入 `backend/app.py` 的 `_FRONTEND_ROUTES` 白名单，否则直接打开与
  刷新会 404（`usage-lock` 任务已踩过一次）。
