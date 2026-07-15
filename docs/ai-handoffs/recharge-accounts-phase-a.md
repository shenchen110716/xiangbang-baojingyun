# 保司分账户充值 Phase A

- task_id: `recharge-accounts-phase-a`
- owner: `Claude Code`
- status: `review`
- branch: `worktree-recharge-accounts-phase-a`
- worktree: `/Users/madisonshen/Desktop/Demo/.claude/worktrees/recharge-accounts-phase-a`
- base_commit: `03a2f24`
- migration_owner: `yes`
- depends_on: `none`
- last_updated: `2026-07-15 Australia/Melbourne`

## 目标

完成保司分账户、企业充值申请、平台审核、余额聚合及对应 Web 页面 Phase A。

## 当前观测状态

- 工作树干净。
- 分支相对 `main` 已有 17 个提交。
- 当前分支提交：`c4ee807`。
- 新增迁移：`c3e7aebc5c9a_add_recharge_accounts.py`。
- 尚未由 Codex 独立验证全量测试和合并结果，因此状态记录为 `review`，不是 `ready`。

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

## 合并前门槛

- `[ ]` Claude Code 更新本交接中的完整测试结果
- `[ ]` Python 系统与安全测试
- `[ ]` Web 正式构建
- `[ ]` 空库初始化及旧库升级
- `[ ]` 充值跨企业隔离和重复确认测试
- `[ ]` Codex 或人工复核公共 API 兼容性

## 下一动作

完成测试和复核后合并到 `main`；合并完成再释放 Alembic 迁移锁和公共模块所有权。
