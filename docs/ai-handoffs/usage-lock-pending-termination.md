# 使用费锁定、待确认停保与短信通知

- task_id: `usage-lock-pending-termination`
- owner: `Codex`
- status: `review`
- branch: `worktree-usage-lock-pending-termination`
- worktree: `/Users/madisonshen/Desktop/Demo/.claude/worktrees/usage-lock-pending-termination`
- base_commit: `717e1a9961a64ee5f2dafa9b14cdad0f32ff44d7`
- migration_owner: `yes`
- depends_on: `recharge-accounts-phase-a（已包含在基线）`
- last_updated: `2026-07-16 09:58 AEST`

## 目标

使用费余额不足时实时锁定参停保写操作，充值后无需额外动作立即恢复；保费账户欠费时生成管理员待确认停保任务，确认后才实际停保；在充值审核、使用费锁定、保费不足和确认停保四类事件发送企业短信通知。

## 范围

- 允许修改：待停保模型与迁移、参停保余额门禁、欠费扫描、短信通知、管理员待停保 API/页面/首页计数、Web 全局 403 通知及对应测试。
- 明确不修改：v4.2 角色分权与及时率模型、Java 镜像、小程序界面、生产配置和生产发布。

## 公共文件与模块

- 实际修改：`backend/app.py`、模型聚合入口、参保/充值/看板路由、服务聚合入口、Web 公共类型/API 客户端/路由、首页和新增待停保页面。
- 已确认与其他活动任务重叠：`role-timeliness-v42` 将修改迁移链、`backend/app.py`、模型聚合入口、参保/看板路由和 Web 公共接口。
- 处理方式：本任务先完成审查与合并；`role-timeliness-v42` 保持 `blocked`，必须从本任务合并后的最新 `main` 创建迁移和工作树。

## 数据库与 API

- 迁移：`f7e2d9b1a4c8_add_pending_terminations.py`，线性接在 `c3e7aebc5c9a` 后；数据库部分唯一索引保证同一企业和账户只存在一条 `pending` 任务。
- API 契约：新增 `GET /api/pending-terminations`、`POST /api/pending-terminations/{id}/confirm`；现有五类参停保写操作在使用费余额 `<= 0` 时返回 403。
- 兼容性：充值后实时读取余额，不持久化锁状态；原有充值、参保和看板响应保持兼容，仅增加 `pending_terminations_count`。

## 提交

- `87c15f2` — 待停保模型与迁移
- `3bc959d` — 实时使用费余额门禁
- `fc6be8a` — 五类参停保端点接入门禁
- `6edbad1` — 保费欠费惰性扫描
- `8344718` — 数据库唯一约束与并发安全
- `da93c7d` / `6cb4056` — 企业短信助手与失败审计恢复
- `77d9fe3` — 待停保查询和确认 API
- `48cbdc4` — 四类短信触发点及按业务日去重
- `8436e0f` — 管理员看板扫描和待处理计数
- `d6a662f` / `2778bce` — Web API 类型与全局锁定通知
- `48a1dd6` / `d5b700b` — 待停保页面与管理员首页入口

## 验证

- `[x]` Python 功能测试：`participation_lock_smoke.py`、`recharge_smoke.py` 通过。
- `[x]` Python 安全测试：`security_smoke.py` 通过。
- `[x]` 既有系统基线：`system_smoke.py` 仍在原有 `payments.py` premium 充值 400 位置失败，未提前为使用费 403。
- `[x]` Web 构建：`npx vue-tsc -b --noEmit && npm run build` 通过。
- `[ ]` Java 测试：本计划明确不镜像 Java，未修改 Java 文件。
- `[ ]` 小程序检查/预览：本计划未修改小程序。
- `[x]` 数据库迁移：单一 Alembic head；目标迁移在 SQLite 现有基线和 PostgreSQL 离线 SQL 验证通过。仓库旧 SQLite 空库全链仍受既有 `96b709380f70` ALTER 约束兼容问题阻塞。
- `[x]` 权限反向测试：非管理员待停保接口拒绝、跨企业门禁和管理员确认路径由烟测覆盖。
- `[x]` 浏览器端到端：管理员首页计数与待停保确认、企业欠费统一通知、余额转正后立即参保均通过隔离数据库验证。

## 风险、阻塞与下一动作

- 风险：`system_smoke.py` 和旧 SQLite 空库全迁移各有一个本任务开始前已存在的基线问题，不能表述为全库零失败。
- 阻塞：当前无本任务实现阻塞；等待整分支代码审查。
- 下一动作：完成整分支审查并修复 Important/Critical 问题；重新跑回归；标记 `ready` 后合并 `main`，再解除 `role-timeliness-v42` 阻塞。
