# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本文件适用于 Claude Code 在“响帮帮保经云”仓库内的所有开发工作。协作协议部分对多代理并行开发是硬约束，改动前务必确认所有权。

## 架构总览（先读这一节）

“响帮帮保经云”是保险经纪管理系统，**三端共用同一个 FastAPI 单体后端**：

- **后端** `backend/app.py` 同时承担三件事：① 提供 `/api/*` 业务接口；② 挂载并服务 Vue 管理端构建产物 `web/dist`；③ 为微信小程序提供同一套 `/api`。
- **Web 管理端** `web/`（Vue 3 + Vite + Element Plus + Pinia + vue-router + ECharts），构建产物落在 `web/dist`，由后端提供。
- **微信小程序** `miniprogram/`（用微信开发者工具打开），默认连 `http://127.0.0.1:8001/api`。
- **Java 后端** `java-backend/`（Maven）是运行时**镜像**，只同步实体与 Mapper，**不建立第二套迁移历史**。

### 关键设计事实（跨多文件，容易踩坑）

- **数据库迁移双轨**：`Python/Alembic` 是结构唯一权威（迁移在 `backend/migrations_alembic/versions/`，`alembic.ini` 在根目录，生产 PostgreSQL 走 Alembic）。但 SQLite 本地/启动另有一套 **运行时兼容桥** `backend/core/migrations.py`（`run_sqlite_bridge_migrations`）。两者不要混淆；新结构变更必须走 Alembic。
- **启动顺序**（`app.py` 的 `startup` 钩子）：`Base.metadata.create_all` → `run_sqlite_bridge_migrations` → `seed_default_accounts` → `migrate_premium_balances`。
- **分层**：`routers/`（HTTP 与鉴权）→ `services/`（业务逻辑）→ `models/`（SQLAlchemy）+ `schemas/`（Pydantic）。横切能力在 `backend/core/`：`config`、`db`、`security`（JWT）、`rbac`（角色/数据范围）、`business_time`（生效规则与有效天数）、`audit`、`file_tokens`（短时签名下载）、`id_number`、`seed`、`migrations`。
- **核心业务服务**（`backend/services/`）：`participation_lock`（使用费余额门禁）、`termination_scan`（保费欠费扫描 → 待确认停保）、`policies` / `policy_members`（保障期生命周期，停保以当前有效 `PolicyMember` 保障期为权威）、`pricing`（保费与有效天数）、`commissions` / `accruals` / `ledger`（佣金与账本）、`recharge`（保司分账户充值）、`notify`（短信/邮件）。
- **外部服务适配**：`backend/providers.py` 统一封装保司 API、短信、邮件、支付；`INTEGRATION_MODE=mock|real` 切换，**默认 `mock`，不会向第三方发真实请求，也不存密钥**。
- **安全姿态**：项目根、源码、`data.db`、`.env` 不得经 HTTP 触达；SPA 走**显式路由白名单**（`app.py` 里的 `_FRONTEND_ROUTES`）而非通配回退；上传的岗位视频/理赔材料**不静态挂载**，只通过短时签名 URL 下载。新增 Web 路由时，记得同步加进 `_FRONTEND_ROUTES` 白名单。

设计与测试详情见 `README.md`、`SYSTEM-DESIGN-V4.2.md`、`SYSTEM-DESIGN-V4.md`、`PRODUCT-DESIGN.md`、`TESTING-GUIDE.md`。

## 常用命令

```bash
# 依赖
python3 -m pip install -r requirements.txt

# 跑后端（开发；协议建议 Claude Code 用 8001 + 独立测试库，Codex 用 8010）
python3 -m uvicorn backend.app:app --host 127.0.0.1 --port 8001 --reload
# 打开 http://127.0.0.1:8001/    OpenAPI: /docs
# 默认账号：admin/admin123（平台端）、enterprise/enterprise123（投保单位端）

# 后端编译/空白检查
python3 -m compileall -q backend
git diff --check

# Web 管理端（改前端后需 build 才会在 8001 生效，后端服务的是 web/dist）
cd web && npm install
npm run dev        # 本地热更新开发
npm run build      # = vue-tsc -b && vite build（正式构建，合并门槛之一）
npx vue-tsc -b --noEmit   # 只做类型检查

# 后端测试：每个 smoke 自带隔离临时 SQLite，直接运行文件即可；跑单个就指定文件名
python3 tests/system_smoke.py            # 系统基线
python3 tests/security_smoke.py          # 安全/权限
python3 tests/participation_lock_smoke.py # 使用费锁定/待确认停保/保障期
python3 tests/recharge_smoke.py          # 充值账户
python3 tests/salesperson_portal_smoke.py # 业务员门户

# 多代理协调只读预检（开始/恢复任务前必跑）
bash scripts/ai_coordination_check.sh

# Java 镜像（Maven）
cd java-backend && mvn -q compile   # 或 mvn test
```

数据库默认 SQLite（`data.db`）；生产用 PostgreSQL（Neon Direct 连接），容器启动时自动执行 Alembic `upgrade`。始终保持单一 Alembic head。

---

# Claude Code 项目协作规则

## 每次开始或恢复任务

1. 完整阅读 `docs/AI-DEVELOPMENT-PROTOCOL.md`。
2. 阅读 `docs/ai-handoffs/` 中所有未结束任务。
3. 运行 `bash scripts/ai_coordination_check.sh`。
4. 检查其他工作树和分支已经修改的文件，再确定本任务允许修改范围。
5. 在独立分支和独立工作树开发，不直接修改 `main`。

## 必须遵守

- 不修改其他代理的工作树，不清理来源不明的文件。
- 不接手已由另一代理声明为 `active` 的任务或公共模块。
- 用户、认证、RBAC、计费、佣金、看板、公共类型、公共路由和数据库迁移必须串行修改。
- 如果两个任务需要同一公共文件，后开始的一方暂停该文件，先处理文档、测试或无依赖部分，并在交接文件中标记阻塞。
- 新 Alembic 迁移必须基于最新已合并迁移；生产执行过的迁移只可追加修正，不可改写。
- **新迁移合并前必须在真实 PostgreSQL 上执行一次**（`python3 scripts/pg_migration_check.py`，需 Neon 凭据）。
  SQLite 通过与离线 SQL 生成都**不足以**证明 PostgreSQL 可用：离线 SQL 只验证语法、不校验类型。
  v4.2 Phase 2 的 `server_default=sa.text("1")` 在布尔列上通过了全部 SQLite 测试，却在生产部署时
  被 PostgreSQL 拒绝。布尔列默认值一律用 `sa.true()`/`sa.false()`，不要用 `text("1")`。
- Python/Alembic 是数据库迁移的唯一权威，Java 只同步运行时模型和 Mapper。

## 交接与发布

- 在 `docs/ai-handoffs/<task>.md` 记录任务状态、范围、修改文件、提交、迁移、测试和风险。
- 不通过测试门槛不得合并。
- 不擅自合并其他代理分支。
- 未经用户对该次发布明确授权，不部署生产环境，不改生产密钥，不上传或提交微信小程序。

## 当前依赖顺序

先完成并合并 `recharge-accounts-phase-a`，之后才开始 `role-timeliness-v42` 的迁移、权限和公共统计模块。
