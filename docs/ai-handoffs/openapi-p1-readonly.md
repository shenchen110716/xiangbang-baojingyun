# 交接：开放平台 P1 只读接口（story-p1-readonly）

- **状态**：实现完成 + **review 返工（attempt 3）已完成**（`docs/stories/review-p1.md` 的 M1/M2/M3 与 m1–m6 全部修复，见下「返工记录」），全部本地测试门槛通过；**未 commit / 未 push / 未切分支**（按编排约定，提交由主管 review 通过后做）。
- **分支**：`feature/openapi-platform`（P0 foundation 已提交为 fbd176a，本次改动以工作区未提交状态交付）。
- **权威规格**：`docs/stories/story-p1-readonly.md`（仓库上级 blueprints/xiangwuyou/docs/stories/）AC1–AC17。
- **前置**：P0 交接文档 `docs/ai-handoffs/openapi-p0-foundation.md`。

## 范围

`/openapi/v1` 只读面：参保名单（列表/详情/历史，status/position_id/updated_since/cursor 过滤，身份证默认脱敏、`insured:read_sensitive` 明文+审计）、异步 XLSX 导出（export_tasks 表 + P0 后台循环 + 300s 签名下载）、保单（列表/详情/download-url/成员）、账户（勘误 1 口径）/流水/发票、独立 OpenAPI schema、文档三件套（usage-guide 勘误 + 4 语言签名示例 + Postman collection）、管理端文档页 banner。

## 修改文件

新增：
- `backend/migrations_alembic/versions/c7d2e9f4a1b8_openapi_export_tasks_p1.py` — export_tasks 表（down_revision=a1f4e8c2d6b0，单一 head）
- `backend/routers/openapi/insured.py` — 名单/详情/历史/导出/导出任务/签名下载
- `backend/routers/openapi/policies.py` — 保单列表/详情/download-url/签名下载/成员
- `backend/routers/openapi/accounts.py` — 账户/流水/发票
- `backend/services/openapi/serializers.py` — 查询构造 + 序列化 + 对外脱敏（mask_id_number_openapi 前3+*+后4）
- `backend/services/openapi/export_tasks.py` — 导出任务入队/后台执行（openpyxl write_only）、卡死回收、48h 留存清理；register_periodic_job("run_openapi_export_tasks" / "cleanup_openapi_export_tasks")
- `backend/services/openapi/audit.py` — 公共敏感审计助手（SENSITIVE_SCOPE / audit_sensitive，review-p1 m5）
- `docs/open-api-postman-collection.json` — Postman v2.1，集合级 CryptoJS 签名 pre-request script，全部只读端点
- `tests/openapi_readonly_smoke.py` — 隔离临时 SQLite，覆盖 AC2–AC15 + AC1 本地迁移

修改：
- `backend/app.py` — 主 schema 反向排除 /openapi/v1（AC15；按生成后 schema["paths"] 过滤，FastAPI 0.139 的 _IncludedRouter 无 .path，routes 级过滤筛不掉）
- `backend/models/openapi.py`、`backend/models/__init__.py` — ExportTask model
- `backend/routers/openapi/__init__.py` — include 新路由 + `GET /openapi/v1/openapi.json` 独立 schema（无需签名）
- `backend/services/openapi/background.py` — import export_tasks 完成 job 注册
- `docs/open-api-usage-guide.md` — banner（P1 已上线/P2 即将上线）、§3 签名勘误（sorted_query 需 URL 编码）+ Java/PHP/Node 示例、§6 导出链路与响应示例、§7 余额口径勘误（premium_accounts 分账户 + usage 计算值，示例与 AC9 实际返回一致）、错误码表、写接口标注 P2
- `web/src/views/openapi/OpenApiDocsView.vue` — el-alert banner「P1 只读接口已上线 / P2 即将上线」

未触碰：`miniprogram/`（project.config.json 的脏状态为任务开始前既有，非本任务产生）。

## 返工记录（review-p1，attempt 3，2026-11-21）

依据 `docs/stories/review-p1.md`（changes_requested）。未新增迁移（M3 按 review 建议用 `created_at < now − 15min` 启发式，单一 head 仍为 c7d2e9f4a1b8）。

**Major：**
- **M1 流水时间口径差 8 小时** — `backend/routers/openapi/accounts.py` `_parse_date`（29–45 行）：不带时区的 `from`/`to` 按文档口径以北京时间（`core/business_time.BUSINESS_TIMEZONE`）解析，带时区按自身偏移，统一 `astimezone(utc)` 折成 UTC naive 后与 `occurred_at` 比较。配套：`backend/services/openapi/serializers.py` 新增 `_iso_utc`（54–61 行），`serialize_ledger_entry.occurred_at` 输出带 `+00:00`。smoke 升级到小时级跨日边界（北京 bj_day 04:00 = UTC 前一日 20:00 的样本流水，北京 [00:00,05:00] 命中 / 从 05:00 排除 / UTC 19:00Z 窗口命中）。
- **M2 明文导出文件权限逃逸** — ① `backend/routers/openapi/insured.py` `get_export_task`（166–196 行）：params_json 固化 `sensitive=true` 的任务，查询/换链接时要求当前 key **仍**持有 `insured:read_sensitive`，否则 403（params 损坏按最严格口径处理）；② 每次发放明文文件下载链接写审计（`export-download-url task={id}`，192–195 行）；③ `backend/services/openapi/export_tasks.py` 新增 `cleanup_export_tasks`（179–198 行）+ `register_periodic_job("cleanup_openapi_export_tasks")`：done/failed 超 48h（`EXPORT_RETENTION_SECONDS`）删落盘文件、任务置 `expired`（删除失败留到下 tick 重试）。
- **M3 卡 processing 永不恢复** — `backend/services/openapi/export_tasks.py` `_recover_stuck_tasks`（120–142 行，`run_export_tasks` 开头调用）：processing 超 15 分钟（`created_at` 启发式，后台循环单实例顺序执行无误回收窗口）第一次重置 pending 重跑（error 列放内部标记 `__timeout_retry__`，路由层只在 failed/expired 透出 error 故不外泄），再超时置 failed + 超时文案。

**Minor：**
- m1 XLSX 公式注入 — `export_tasks.py` `_safe_cell`（65–71 行）：`= + - @` 开头的字符串加 `'` 前缀转义，`_build_insured_xlsx` 全字段过一遍。
- m2 `task.error` 不透传 `str(exc)` — `export_tasks.py` 失败路径（168–174 行）对外只给通用文案 `GENERIC_FAILED_MESSAGE`，原始异常 `logger.exception` 按 task.id 进服务端日志。
- m3 members N+1 与审计导致逐行 re-SELECT — `backend/routers/openapi/policies.py` members（126–135 行）批量 `IN` 取人；审计统一移到序列化之后（policies.py 149–153、insured.py 75–80）。
- m4 导出改 `openpyxl.Workbook(write_only=True)` + `yield_per(500)` 流式写（`_build_insured_xlsx`，104–117 行）。
- m5 不 import 私有 `_audit_sensitive` — 提成公共模块 `backend/services/openapi/audit.py`（`SENSITIVE_SCOPE` / `audit_sensitive`），insured.py 与 policies.py 均从此 import。
- m6 updated_since 过度包含 — `docs/open-api-usage-guide.md` §4 如实注明「宁滥勿缺」口径与按 id 幂等合并的建议（代码不改）。

**文档同步**：usage-guide §4 新增统一时间口径说明（裸时间=北京、响应=UTC+00:00、查询串 `+` 需编码）；§6.1 导出状态机补 `expired`、15 分钟超时回收、明文任务降权即不可续取、48h 文件留存；§7.1 流水 from/to 与 occurred_at 口径含示例。文档 ?raw 内联进 web bundle，已重跑 `npm run build`。

**smoke 新增覆盖**（`tests/openapi_readonly_smoke.py`）：M1 小时级跨日边界三断言 + occurred_at 后缀断言；M2① 无 sensitive scope 的 key 查明文任务 403 负例；M2② `export-download-url` 审计行断言；M2③ `test_export_retention_cleanup`（49h 前完成的任务 → tick → expired/文件删除/旧签名 URL 404）+ 启动时断言 cleanup job 已注册；M3 `test_export_stuck_recovery`（超时 processing → 重跑成功；带重试标记的超时 → failed + 超时文案，API 透出）；m1 注入样本 `=HYPERLINK(...)` 在导出文件中以 `'=` 文本落盘断言。

## 迁移

- revision `c7d2e9f4a1b8`，down_revision `a1f4e8c2d6b0`，单一 head（`python3 -m alembic heads` 验证）。
- export_tasks：全列 NOT NULL + server_default（status 'pending'、task_type 'insured_persons'、params_json/file_url/error ''、created_at `sa.func.now()`），finished_at nullable；索引 ix_export_tasks_enterprise_id、ix_export_tasks_status；无布尔列。
- ⚠️ **真实 PostgreSQL 验证未在本机做**（前门决定：由编排方在生产 VPS 的 PG16 上以生产库为模板建临时库跑 `alembic upgrade` 后删除）。本地已验证：隔离 SQLite 上 stamp a1f4e8c2d6b0 → upgrade head 走通（smoke 内子进程断言）。

## 测试（全部真实 exit code = 0，返工后 2026-11-21 复跑）

| 命令 | exit code |
|---|---|
| `python3 tests/openapi_readonly_smoke.py` | 0 |
| `python3 tests/openapi_foundation_smoke.py` | 0 |
| `python3 tests/system_smoke.py` | 0 |
| `python3 tests/security_smoke.py` | 0 |
| `python3 -m compileall -q backend` | 0 |
| `git diff --check` | 0 |
| `python3 -m alembic heads`（单一 head c7d2e9f4a1b8） | 0 |
| `cd web && npm run build` | 0 |

新 smoke 覆盖：签名正/负例；AC14 五面缺 scope 403；列表过滤/游标分页走查/状态语义/脱敏格式；read_sensitive 明文 + audit_logs(action=openapi_read_sensitive, user_id=NULL) 断言；详情 coverage + history 时间线；AC6 跨租户六个面（人员/保单/账户/流水/发票/导出）404 模糊 + 列表隔离；导出全链路（入队→run_tick_once→done→签名下载→openpyxl 解析断言脱敏与数据隔离，sensitive key 导出明文，过期/篡改 token 403）；导出限流第 11 次 429+Retry-After+42900；AC9 返回与 premium_accounts_for_enterprise()/usage_account_view() 直算逐字段一致；流水 account/from 过滤；发票；保单无定价字段、download-url TTL≤300s、无文件 42200、成员脱敏；AC15 双向 schema 分离；迁移隔离验证。

## 设计决策与风险

1. **updated_since 为业务事件近似**：insured_people 无 updated_at 列，按「建档 / 保障期写入或终止 / 参停保操作提交或确认 ≥ since」的 EXISTS 口径命中（serializers.insured_persons_query）。naive 业务时钟列按北京口径比较、tz-aware 列按 UTC。文档 §4 已按此口径写明。若未来加 updated_at 列可换成精确口径。
2. **对外脱敏口径独立**：mask_id_number_openapi（前3+*+后4）比内部 core/id_number.mask_id_number（前6后4）更保守，两者并存、互不影响。
3. **导出脱敏口径入队固化 + 取用时复核**：sensitive 由入队那一刻 key 的 scope 决定并写进 params_json，后台执行不回查 scope；但明文口径任务在**每次查询/换下载链接时**要求当前 key 仍持有 `insured:read_sensitive`（review-p1 M2），降权后旧明文文件立即不可续取，且每次发放明文链接写审计；文件 48h 后由清理 job 删除并置 expired。
4. **签名下载端点不走签名头**：export-files / policy-files 以短时 token 即授权（include_in_schema=False，仍 OpenApiRoute 统一包）；与 /api 面 reports/claims 的先例一致。
5. **文档签名勘误**：原 usage-guide 的 Python 示例 sorted_query 未 URL 编码，与服务端 canonical_query（urlencode(sorted(parse_qsl(...)))）不一致，已修正并在 4 语言示例 + Postman 脚本中对齐（Postman 脚本补了 encodeURIComponent 与 quote_plus 的差异字符 `!'()*` 和空格→+）。
6. **schema 分离的实现约束**：FastAPI 0.139 include_router 产物是惰性 _IncludedRouter（无 .path），主 schema 过滤必须在生成后按 schema["paths"] 做（app.py:138–150），不要改回 routes 级过滤。
7. **遗留**：Postman 集合只含只读端点，写端点随 P2 补；Java 镜像（java-backend/）未同步 ExportTask 实体（P0 时同样未同步 openapi 三表，建议 P2 一并处理）；生产 PG 迁移验证待编排方执行（见上）。
