# 及时率报表、数据质量与导出 v4.2 Phase 4

- task_id: `timeliness-reporting-phase4`
- owner: `Claude Code`
- status: `merged`（已合并、已推送、已发布生产）
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

## 提交

- `3236c6d` — 红灯报表契约 + CONTRACT-PROVISIONAL 校准
- `9843912` — 报表聚合服务与带审计的 XLSX 导出
- `ffb228d` — 企业主管 Web 页面 + SPA 白名单结构性守卫
- `a3bd68d` — 小程序项目负责人只读页面

## 验证（2026-07-17，均在最终提交状态上执行）

- `[x]` `timeliness_reporting_test`（8）：卡片字段完整、反馈率、操作员可归责率、
  缺口与额外保费求和、项目负责人范围、筛选、导出脱敏、导出摘要与文件一致
- `[x]` `frontend_routes_test`：24 个 Vue 路由全部在 `_FRONTEND_ROUTES` 中；
  **已反证**：临时移除条目后测试变红并指出缺失路由
- `[x]` Phase 3 回归：`timeliness_smoke`、`timeliness_engine_test`、`timeliness_rules_test`、
  `timeliness_responsibility_test`、`timeliness_model_test`
- `[x]` Phase 1/2 回归：`batch_owner_only_test`、`employment_facts_smoke`、
  `employment_import_test`、`employment_integration_test`、`employer_scope_smoke`
- `[x]` 既有回归：`security_smoke`、`system_smoke`、`recharge_smoke`、
  `participation_lock_smoke`、`salesperson_portal_smoke`、`agent_pricing_visibility_test`
- `[x]` `web/npm run build`、`compileall`、`git diff --check`
- `[x]` **Alembic head 未变**，仍为 `7f0a1fa05267`（本阶段不建迁移）
- `[x]` **导出文件打开验证**（§18）：实际生成并用 openpyxl 打开，18 列、表头正确、
  身份证仅脱敏形式、原文不存在、审计摘要与文件字节一致
- `[x]` 小程序语法与 JSON 校验通过
- `[ ]` 小程序编译预览：未在开发者工具中实机预览（见风险）；**未上传、未提交**

## 合并与发布（2026-07-17）

- 合并提交：`c1172c9`，无冲突；合并后在 `main` 上重跑 12 项测试、`web/npm run build`、
  `compileall` 全部通过，head 仍为 `7f0a1fa05267`。
- 推送 `81b6eeb..c1172c9`，经 Render 自动部署成功（事件 `deploy_ended: succeeded`；
  注意 `deploys?limit=1` 的 status 字段有滞后，以事件为准）。
- 生产验证：路由 107 → **108**（新增 `/api/timeliness/export`）；五条及时率端点未带 token
  均返回 401；**`/timeliness` 直接打开返回 200**（SPA 白名单生效，页面可收藏可刷新），
  而 `/nonexistent-page` 返回 404（未退化为通配回退）。

## 与计划的差异

- 计划的 `tests/timeliness_reporting_smoke.py` 以 `tests/timeliness_reporting_test.py`
  实现：直测服务层，断言覆盖相同（含导出脱敏与审计元数据），但不起 HTTP 服务，更快。
  端点层的鉴权由 `batch_owner_only_test` 与既有安全烟测覆盖。

## 已知风险

- **导出同步且无上限**：大企业导出可能在 Render 免费套餐上超时。当前无分页与异步任务，
  行数多时需要改为后台生成 + 短时签名下载。
- ~~**Outbox 仍无调度器**~~ 已由 `fix/outbox-wiring`（`4dbe6cd`）修复并发布：
  导入与纠错现在会入队，summary/details 读取前惰性排空。仍无定时 Worker，
  但读取路径保证了看到的数字是最新的。
- 小程序页面已写并通过语法/JSON 校验，但**未在微信开发者工具中实机预览**；
  按 `CLAUDE.md` 未上传、未提交。
- **PostgreSQL 门槛仍未启用**：本阶段不建迁移故不受影响；Phase 5 将新建结算/付款表，
  届时应先就绪。

## 风险与阻塞

- **PostgreSQL 门槛仍未启用**：`scripts/pg_migration_check.py` 缺凭据。本阶段不建迁移，
  不受影响；但 Phase 5 将新建结算/付款表，届时应先就绪。
- 新 Web 路由必须同步加入 `backend/app.py` 的 `_FRONTEND_ROUTES` 白名单，否则直接打开与
  刷新会 404（`usage-lock` 任务已踩过一次）。
