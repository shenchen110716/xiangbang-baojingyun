# 保司分账户充值 Phase A

- task_id: `recharge-accounts-phase-a`
- owner: `Claude Code`
- status: `merged`
- branch: `worktree-recharge-accounts-phase-a`
- worktree: `/Users/madisonshen/Desktop/Demo/.claude/worktrees/recharge-accounts-phase-a`
- base_commit: `03a2f24`
- migration_owner: `no（已释放）`
- depends_on: `none`
- last_updated: `2026-07-16 10:45 AEST`

## 目标

完成保司分账户、企业充值申请、平台审核、余额聚合及对应 Web 页面 Phase A。

## 当前观测状态

- `c4ee807` 已由合并提交 `788baf3` 纳入 `main`。
- 新增迁移 `c3e7aebc5c9a_add_recharge_accounts.py` 已位于当前唯一迁移链中。
- Codex 在 `main@1c223e3` 复核 `git merge-base --is-ancestor c4ee807 main` 返回成功。
- 合并后的充值、系统、安全、使用费锁、业务员门户烟测和 Web 构建均通过。
- 原充值工作树已不在注册工作树列表中，迁移锁和公共模块所有权已释放。

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

本任务已合并；后续任务必须从包含 `788baf3` 的最新 `main` 继续。

## 主要提交

- `c4ee807` — 首页按账户展示保费余额
- `f25899f` — 企业充值中心页面及审核入口
- `edecd7e` — 保司账户管理 UI
- `932d0d9` — 充值申请审核接口
- `1d7fc4b` — 保司账户与映射接口
- `1a4c5e1` — 充值账户表迁移
- `59544c3` — 充值账户模型

完整提交列表以该分支 Git 历史为准。

## 合并门槛

- `[x]` 充值功能合并到 `main`
- `[x]` Python 系统与安全测试
- `[x]` Web 正式构建
- `[x]` 充值跨企业隔离和重复确认测试
- `[x]` Codex 复核公共 API 兼容性
- `[x]` 后续使用费任务基于该迁移头继续并完成合并回归

## 下一动作

本任务已完成合并并释放 Alembic 迁移锁和公共模块所有权；等待统一发布窗口，不单独部署。
