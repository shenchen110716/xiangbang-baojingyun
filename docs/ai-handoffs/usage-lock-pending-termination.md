# 使用费锁定、待确认停保与短信通知

- task_id: `usage-lock-pending-termination`
- owner: `Codex`
- status: `review`
- branch: `fix/usage-lock-review`（基于 Claude Code 交接分支）
- worktree: `/private/tmp/xiangbang-usage-review`
- base_commit: `717e1a9961a64ee5f2dafa9b14cdad0f32ff44d7`
- migration_owner: `yes`
- depends_on: `recharge-accounts-phase-a（已包含在基线）`
- last_updated: `2026-07-16 10:11 AEST`

## 目标

使用费余额不足时实时锁定参停保写操作，充值后无需额外动作立即恢复；保费账户欠费时生成管理员待确认停保任务，确认后才实际停保；在充值审核、使用费锁定、保费不足和确认停保四类事件发送企业短信通知。

## 范围

- 允许修改：待停保模型与迁移、参停保余额门禁、欠费扫描、短信通知、管理员待停保 API/页面/首页计数、Web 全局 403 通知、被动停保生命周期调用及对应测试。
- 明确不修改：v4.2 角色分权与及时率模型、Java 镜像、小程序界面、生产配置和生产发布。

## 公共文件与模块

- 实际修改：`backend/app.py`、模型聚合入口、参保/充值/看板路由、保单生命周期服务、服务聚合入口、Web 公共类型/API 客户端/路由、首页和新增待停保页面。
- 已确认与其他活动任务重叠：`role-timeliness-v42` 将修改迁移链、`backend/app.py`、模型聚合入口、参保/看板路由和 Web 公共接口。
- 处理方式：本任务先完成审查与合并；`role-timeliness-v42` 保持 `blocked`，必须从本任务合并后的最新 `main` 创建迁移和工作树。

## 数据库与 API

- 迁移：`f7e2d9b1a4c8_add_pending_terminations.py`，线性接在 `c3e7aebc5c9a` 后；数据库部分唯一索引保证同一企业和账户只存在一条 `pending` 任务。
- API 契约：新增 `GET /api/pending-terminations`、`POST /api/pending-terminations/{id}/confirm`；现有五类参停保写操作在使用费余额 `<= 0` 时返回 403。
- 兼容性：充值后实时读取余额，不持久化锁状态；原有充值、参保和看板响应保持兼容，仅增加 `pending_terminations_count`。

## Codex 独立审查与修复

Claude Code 交接提交未直接合并。Codex 在独立工作树完成整分支审查后发现并修复：

- Critical：欠费任务按账户生成，但原确认逻辑会停止企业全部在保人员；现按 `InsuredPerson.policy_id -> Policy.plan_id -> InsurancePlan.insurer -> InsurerAccountLink` 精确限定欠费账户。
- Critical：原确认逻辑没有重新检查账户余额；现锁定待办和余额行，余额已恢复时自动撤销并返回 409，避免充值与确认竞态造成误停。
- Critical：被动欠费停保仍调用自愿停保时间校验，真实保单可能整批确认失败；现使用默认严格、仅本场景显式关闭时间校验的生命周期参数。
- Important：直接打开待停保页面不会触发惰性扫描；现与管理员首页一样触发扫描。
- Important：页面只显示企业 ID，无法核对账户和人员；现返回企业名、账户名、当前人数和人员名单。
- Important：新增 Web 路由没有加入后端 SPA 允许列表；现补齐 `/recharge`、`/pending-terminations`、`/agent-portal`，支持直接打开和刷新。
- Minor：批量新增传入不存在企业时可能 500，空手机号兼容性不足；均已修复。

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
- `待提交` — Codex 账户范围、余额复验、被动停保、页面入口及回归修复

## 验证

- `[x]` Python 功能测试：`participation_lock_smoke.py`、`recharge_smoke.py` 通过。
- `[x]` Python 安全测试：`security_smoke.py` 通过。
- `[x]` 既有系统基线：修正失效的旧保费支付烟测为仍受支持的使用费支付回调后，`system_smoke.py` 通过。
- `[x]` 业务员门户回归：`salesperson_portal_smoke.py` 通过。
- `[x]` Web 构建：`npx vue-tsc -b --noEmit && npm run build` 通过。
- `[x]` Java 回归：Maven 编译与测试生命周期通过（当前无 Java 测试类，本计划不镜像 Java）。
- `[ ]` 小程序检查/预览：本计划未修改小程序。
- `[x]` 数据库迁移：单一 Alembic head `f7e2d9b1a4c8`；目标迁移的 SQLite/PostgreSQL 离线 SQL 均通过；临时 SQLite 运行时空库由全套烟测初始化通过。仓库旧 SQLite 纯 Alembic 空库全链仍有既有 `96b709380f70` ALTER 兼容问题。
- `[x]` 权限与业务反向测试：非管理员接口拒绝、跨企业门禁、多账户防误停、充值后禁止确认、无归属历史人员不误停均覆盖。
- `[x]` 浏览器端到端：管理员首页计数与待停保确认、企业欠费统一通知、余额转正后立即参保均通过隔离数据库验证。

## 风险、阻塞与下一动作

- 风险：旧 SQLite 纯 Alembic 空库全链仍有本任务开始前的兼容问题；生产 PostgreSQL 使用线性迁移且本次 SQL 已验证。短信仍为同步尽力发送，不是消息队列。
- 阻塞：当前无实现阻塞；等待修复提交、合并兼容检查及 `main` 合并后回归。
- 下一动作：提交 Codex 审查修复；与最新 `main` 合并；在 `main` 重跑关键回归；标记 `merged` 后解除 `role-timeliness-v42` 阻塞。
