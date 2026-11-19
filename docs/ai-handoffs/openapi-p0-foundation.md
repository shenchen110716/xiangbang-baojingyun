# 开放平台 P0 地基（story-p0-foundation）

- task_id: `openapi-p0-foundation`
- owner: `Claude Code`
- status: `review`
- branch: `feature/openapi-platform`
- worktree: `Demo/`（编排方管理，本任务未另开工作树）
- base_commit: `9dbd21a`（main）
- migration_owner: `yes`（新迁移 `a1f4e8c2d6b0`，down_revision=`9c3d1e5f7a2b`，单一 head）
- depends_on: `none`
- last_updated: 2026-11-19（由编排 run r-20261119-openapi-platform step 3 产出；
  同日按 `docs/stories/review-p0.md` 完成 attempt 2 返工，见「评审返工」节）

## 目标

对外开放平台的 P0 地基：签名 v2 认证（/openapi/v1）、统一响应包与错误码
分段、scope/IP 白名单/DB 固定窗口限流、请求审计日志、管理端密钥签发界面、
app.py lifespan 改造 + 统一后台任务循环（nonce/日志清理）、沙箱环境路由。
按 `docs/stories/story-p0-foundation.md`（AC1–AC17）与
`Demo/docs/open-api-platform-design.md` §0–§9 实现。

## 范围

- 允许修改：backend（app.py、core、models、routers、services、迁移）、
  web/src（api、views）、tests。
- 明确不修改：`miniprogram/`（含其既有 project.config.json 改动）、
  既有 §7.3 v1 用工事件端点行为、`main` 分支。未 commit/push/切分支
  （编排方在 review 后统一提交）。

## 公共文件与模块

- 计划修改并已修改：`backend/app.py`（lifespan，公共文件、串行点）、
  `backend/models/__init__.py`、`backend/core/api_keys.py`（只增不改 v1）、
  `backend/core/migrations.py`（桥尾部追加索引）、
  `web/src/api/types.ts`（尾部追加接口）、
  `web/src/views/enterprises/EnterprisesPanel.vue`（dropdown + dialog 挂载点）。
- 已确认与其他活动任务重叠：`bash scripts/ai_coordination_check.sh` 预检
  0 个重叠文件、0 个其他含迁移的分支。
- 处理方式：不适用（无重叠）。

## 数据库与 API

- 迁移：`backend/migrations_alembic/versions/a1f4e8c2d6b0_openapi_foundation_p0.py`
  - 新表 `openapi_clients`（key_id 唯一、secret_encrypted/
    secondary_secret_encrypted Fernet 密文、rotated_at、enterprise_id FK、
    scopes/allowed_employer_ids/ip_allowlist CSV、status、expires_at、
    env prod|sandbox、created_at）、`openapi_request_logs`（含 created_at
    索引）、`openapi_rate_counters`（(key_id,bucket,window_start) 唯一）。
  - `integration_nonces` 补 `ix_integration_nonces_seen_at`（清理任务前提）；
    旧 SQLite 本地库由 `core/migrations.py` 桥尾部 `CREATE INDEX IF NOT
    EXISTS` 补齐（新表走 create_all）。
  - 无布尔列；保持单一 head（`alembic heads` = a1f4e8c2d6b0）。
  - **真实 PostgreSQL 验证未在本机执行**（Pod 无 PG；按编排约定由编排方在
    生产 VPS 临时库跑 `alembic upgrade head` 验证后才算过迁移门槛）。
    隔离 SQLite 上已验证：stamp 9c3d1e5f7a2b → upgrade head 建表全部走通
    （smoke 内置该检查）。
- API 契约：
  - 对外 `/openapi/v1/ping`（GET，任意有效 key）、`/openapi/v1/scope-probe`
    （GET，需 scope `probe:read`）。签名 v2：
    `METHOD\nPATH\nsorted_query\nX-Timestamp\nX-Nonce\nsha256(body)`，
    头 `X-Api-Key-Id / X-Timestamp / X-Nonce / X-Signature / X-Sign-Version: 2`，
    ±300s 时钟窗，nonce 一次性。统一包
    `{"code","message","data","request_id"}`，错误码 40100/40300/42200/42900/50000，
    HTTP 状态 = code//100。签名/认证类失败一律 401+40100（含禁用/过期 key）；
    429 带 `Retry-After`。
  - 管理端（/api，admin）：`POST /api/openapi-clients`（签发，secret 仅此一次）、
    `GET /api/openapi-clients?enterprise_id=`、`PATCH /api/openapi-clients/{id}`
    （启停/scope/IP/过期）、`GET /api/openapi-request-logs`。签发/启停写
    audit_logs（openapi_client_issue / openapi_client_disable / _enable / _update）。
- 兼容性：
  - §7.3 v1 端点与签名原语未动（employment_integration_test 全绿）。
  - `@app.on_event("startup")` 改为单一 lifespan；模块级 `startup()` 保留，
    存量 `from backend.app import startup; startup()` 契约不变。
  - api_client 主体是独立 `OpenApiPrincipal`，不建 users 行、不发 JWT、
    不进 current_user 负向角色过滤；openapi 查询显式
    `enterprise_id = principal.enterprise_id`。
  - 沙箱：env=sandbox 的 key 只能绑 `Enterprise.kind == "沙箱"` 的企业
    （复用现有 kind 字段，无额外 schema；管理端下拉暂只有 企业/HR机构，
    沙箱企业可由后端/后续界面创建——见风险）。

## 评审返工（attempt 2，对应 review-p0.md）

- **B1（已修）XFF 伪造绕过 IP 白名单** → 可信代理模型
  （`services/openapi/request_log.py::client_ip`）：默认用直连 socket IP；
  仅当直连来源 ∈ `OPENAPI_TRUSTED_PROXIES`（CSV IP/CIDR，默认
  `127.0.0.1`，匹配生产 nginx 同机回环形态）时取 XFF **最后一跳**
  （nginx `$proxy_add_x_forwarded_for` 是追加语义，最后一跳才是反代写入的
  真实来源）。smoke 原「伪造 XFF 预期 200」用例改为 1 正 2 负。
- **M1（已修）advisory lock 连接漂移** → `background.AdvisoryLock` 用
  `engine.connect()` 独立持有一条裸连接，lock/unlock 保证同一连接；解锁
  失败 `conn.invalidate()`，绝不带锁回池；`run_locked_tick()` 包住整个
  tick，`background_loop` 改用它。smoke 用假 PG 引擎断言同连接
  lock+unlock、tick 后连接关闭、抢锁失败跳过本轮。
- **M2（已修）openapi_rate_counters 无清理** → 新增
  `cleanup_openapi_rate_counters` job（删 `window_start` 老于 1 天的行），
  smoke 断言旧行删、新行留。
- **M4（已修）静默吞异常** → `common.py` 50000 路径、`background.py`
  job/tick 失败、`request_log.py` 写日志失败均 `logger.exception`
  （logger `backend.openapi`，带 request_id/path/job 名）；响应体仍不回显
  内部细节。
- **M3（未动，非本任务范围）**：`miniprogram/project.config.json` 保持
  工作区原样，由编排方/人类处置。
- **minor-2（已修）**迁移两处 `created_at` 改 `nullable=False,
  server_default=sa.func.now()`（NULL 行会永远躲过按时间清理）。
- **minor-4（已修）**请求日志落盘改 `await run_in_threadpool(...)`，
  不再在事件循环里做同步 DB 写。
- **minor-5（已修）**`/openapi/v1` 下未匹配路径加兜底路由，404 也走统一包
  （42200）并落请求日志；smoke 有断言。
- **minor-6（已修）**未知 key_id 路径先对随机假 secret 跑一次等价 HMAC
  再回 401，对齐「已知 key 错签名」的耗时，堵时延枚举侧信道。
- **minor-1（不改，已记录）**分层倒置：`services/openapi/*` 从
  `routers/openapi/common` 导入 OpenApiError/错误码。story File Map 即把
  统一包助手定在 routers 下，评审认定 P0 可接受、结构调整报人类裁决；
  后续如抽到 `backend/core/openapi_errors.py` 需同步改 5 个文件的 import。
- **minor-3（不改，已记录）**沙箱判定依赖 `Enterprise.kind == "沙箱"`
  自由文本（常量 `SANDBOX_ENTERPRISE_KIND` 已集中在 openapi_admin.py）。
  kind 本就是可编辑自由文本、无 enum 约束；收紧（enum/专用布尔列）涉及
  公共表结构，按评审意见留到 P1 前处理。

## 提交

- 无（按编排指令本步骤不 commit；工作树保持待提交状态交由 review）。

## 验证

- `[x]` `python3 tests/openapi_foundation_smoke.py` → exit 0（新增；覆盖
  AC2–AC10、AC12–AC16，含签名负例 5 类、越权隔离、限流 429、tick 清理、
  lifespan 启停、隔离 SQLite 迁移走通）
- `[x]` `python3 tests/system_smoke.py` → exit 0
- `[x]` `python3 tests/security_smoke.py` → exit 0
- `[x]` `python3 tests/recharge_smoke.py` → exit 0
- `[x]` `python3 tests/employment_integration_test.py` → exit 0（v1 回归，
  uvicorn 子进程真实走新 lifespan）
- `[x]` `python3 tests/frontend_routes_test.py` → exit 0
- `[x]` `python3 -m compileall -q backend` → exit 0；`git diff --check` → exit 0
- `[x]` `cd web && npm run build`（vue-tsc -b && vite build）→ exit 0
  （attempt 1 执行；attempt 2 返工未触碰 web/，无需重跑）
- 以上后端门槛在 attempt 2 返工后已全部重跑，均 exit 0；
  `alembic heads` 仍为单一 head `a1f4e8c2d6b0`
- `[ ]` `python3 scripts/pg_migration_check.py`（真实 PostgreSQL）——
  待编排方在 VPS 执行，合并前必须完成

## 修改文件

新增：`backend/models/openapi.py`、
`backend/migrations_alembic/versions/a1f4e8c2d6b0_openapi_foundation_p0.py`、
`backend/routers/openapi/{__init__,common,meta}.py`、
`backend/routers/openapi_admin.py`、
`backend/services/openapi/{__init__,auth,rate_limit,request_log,background}.py`、
`web/src/api/openapiAdmin.ts`、`web/src/views/openapi/OpenApiClientsPanel.vue`、
`tests/openapi_foundation_smoke.py`、本文件。

修改：`backend/app.py`、`backend/core/api_keys.py`、`backend/core/migrations.py`、
`backend/models/__init__.py`、`backend/models/employment.py`、
`web/src/api/types.ts`、`web/src/views/enterprises/EnterprisesPanel.vue`。

## 风险与注意

1. **PG 迁移未本机验证**（见上，合并前门槛）。迁移无布尔列、无 ALTER，
   风险面小，但必须跑。
2. **IP 判定为单级可信代理模型**（B1 返工后）：`OPENAPI_TRUSTED_PROXIES`
   默认 `127.0.0.1`，匹配当前「nginx 与应用同机」形态。若未来加 CDN 等
   多级代理，取「XFF 最后一跳」拿到的是 CDN 节点 IP——届时需按可信层数
   剥链（或改信 CDN 专用真实 IP 头），并同步扩 `OPENAPI_TRUSTED_PROXIES`。
3. **限流为固定窗口**：窗口边界理论上可被 2×limit 突刺；P0 按设计方案
   接受（10 QPS/key 档位）。限额/窗口可用环境变量
   `OPENAPI_RATE_LIMIT_PER_SEC` / `OPENAPI_RATE_LIMIT_WINDOW_SECONDS` 调。
4. **沙箱企业的创建入口**：判定复用 `Enterprise.kind == "沙箱"`（minor-3，
   见「评审返工」节）；管理端新增企业下拉目前只有「企业/HR机构」，建沙箱
   企业需后端或直接改 kind（kind 本就是自由文本、可编辑）。后续 story
   建议补「沙箱」选项或收紧为 enum/专用标记。
5. **分层倒置未调**（minor-1，见「评审返工」节）：services/openapi 依赖
   routers/openapi/common 的错误类型；按 story File Map 实现、评审定为
   P0 可接受，结构调整报人类裁决。
6. 测试文件名：编排 brief 提过 `tests/openapi_p0_smoke.py`，story AC17/
   File Map 写 `tests/openapi_foundation_smoke.py`——按 story（权威）命名。
7. CORS 中间件对 /openapi/v1 同样生效（全站 `*`）；签名认证不依赖
   cookie，无 CSRF 面，暂无需单独收紧。
8. **后台任务可观测性**：job/tick 失败现在会 `logger.exception` 落服务端
   日志（logger `backend.openapi`），但没有告警管道；若清理任务长期失败
   仍需人工看日志发现。P1 可考虑接现有 notify 渠道。
