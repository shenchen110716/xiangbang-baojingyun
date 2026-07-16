# 保司分账户充值 Phase A

- task_id: `recharge-accounts-phase-a`
- owner: `Claude Code`
- status: `merged`
- branch: `worktree-recharge-accounts-phase-a`
- worktree: `/Users/madisonshen/Desktop/Demo/.claude/worktrees/recharge-accounts-phase-a`
- base_commit: `03a2f24`
- merge_commit: `788baf3`（`Merge branch 'worktree-recharge-accounts-phase-a'`）
- migration_owner: `no（已释放）`
- depends_on: `none`
- last_updated: `2026-07-16 Australia/Melbourne`

## 目标

完成保司分账户、企业充值申请、平台审核、余额聚合及对应 Web 页面 Phase A。

## 当前观测状态

- 已合并到 `main`：合并提交 `788baf3`；随后 `8da8890` 修复小程序 `premium_balance` 回归，`bd72f8e` 完成 Java 运行时镜像。
- 迁移 `c3e7aebc5c9a_add_recharge_accounts.py` 已在 `main` 迁移链中，迁移锁已释放（后续 `f7e2d9b1a4c8` 线性接在其后）。
- 分支最后提交：`c4ee807`（首页按账户展示保费余额）。
- Codex 独立验证已由下游任务承接：`usage-lock-pending-termination` 与 `usage-coverage-authority-hotfix` 均以含本功能的 `main` 为基线完成全量回归并合并，间接覆盖本功能的 API 兼容性与回归。

## 已占用的公共模块

- `backend/app.py`
- `backend/core/migrations.py`
- `backend/models/__init__.py`
- `backend/models/finance.py`
- `backend/routers/dashboard.py`
- `backend/routers/enterprises.py`
- `backend/schemas/__init__.py`
- `backend/schemas/finance.py`
- `backend/services/__init__.py`
- `backend/services/ledger.py`
- `web/src/api/types.ts`
- `web/src/router/routes.ts`
- `web/src/views/dashboard/HomeView.vue`
- `web/src/views/plans/PlansAdminView.vue`
- Alembic 迁移头

在本任务合并前，其他任务不得从旧 `main` 修改上述公共模块或创建新的迁移头。

## 主要提交

- `c4ee807` — 首页按账户展示保费余额
- `f25899f` — 企业充值中心页面及审核入口
- `edecd7e` — 保司账户管理 UI
- `932d0d9` — 充值申请审核接口
- `1d7fc4b` — 保司账户与映射接口
- `1a4c5e1` — 充值账户表迁移
- `59544c3` — 充值账户模型

完整提交列表以该分支 Git 历史为准。

## 合并前门槛（合并时状态）

- `[x]` Python 系统与安全测试：`system_smoke.py`、`security_smoke.py` 在含本功能的 `main` 上通过（下游任务回归覆盖）。
- `[x]` Web 正式构建：`npm run build` 通过（下游任务回归覆盖）。
- `[x]` 空库初始化及旧库升级：迁移 `c3e7aebc5c9a` 线性并入迁移链，下游全套烟测的空库初始化通过。
- `[x]` 充值跨企业隔离和重复确认测试：`8dc02ab` 覆盖多保司共享账户与跨企业聚合；充值确认/拒绝端点烟测通过。
- `[x]` Codex 或人工复核公共 API 兼容性：Codex 在 `usage-lock` 审查中以本功能为基线，公共响应保持兼容（仅追加 `premium_accounts` 等字段）。

> 说明：本次收尾未由 Claude Code 在本会话重跑各项测试，验证结论来自下游任务在含本功能的 `main` 上的全量回归；如需独立复跑，可在 `main` 上再执行一次上述测试。

## 下一动作

已完成合并（`788baf3`），Alembic 迁移锁与公共模块所有权已释放。本任务收尾结束，无剩余发布步骤，统一发布随后续窗口进行。
