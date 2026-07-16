# 真实用工事实与两阶段导入 v4.2 Phase 2

- task_id: `employment-facts-phase2`
- owner: `Claude Code`
- status: `merged`（已合并到本地 main，**尚未推送、尚未部署**）
- branch: `feat/employment-facts-phase2`
- worktree: `/private/tmp/xiangbang-employment-facts`
- base_commit: `8bc04e0`
- migration_owner: `no（已释放；迁移 c40dab695a66 已合并进 main，尚未在生产执行）`
- depends_on: `role-timeliness-v42 Phase 1（已合并发布）`
- last_updated: `2026-07-17`

## 目标

存储按人版本化的真实入离职事实，经原子的两阶段预览/确认导入写入，附带身份证保护，
并提供带签名认证的外部用工事件接口——为 Phase 3 的及时率计算提供权威事实基线。

执行计划：`docs/superpowers/plans/2026-07-17-employment-facts-phase.md`（9 个任务，全部完成）。

## 提交

- `baa6b67` — 红灯契约测试（`tests/employment_facts_smoke.py`）
- `1fe5f9c` — 身份证哈希、加密与脱敏原语，新增 `ID_ENCRYPTION_KEY`
- `1dae9e0` — 用工事实迁移与模型（三张表）
- `35a5dbb` — 事实服务：版本化、纠错、权威查询
- `2c7e4c4` — 身份匹配优先级阶梯
- `a48d8c9` — 修正重复文件防护索引谓词；抽取 `read_import_rows` 到 services
- `e263264` — 两阶段原子导入
- `fc15d69` — 事实与批次 API（§14.2）
- `111ceb4` — 外部用工事件 API（§7.3）

## 数据库

- 迁移 `c40dab695a66`，`down_revision = d5a4c12f7b91`（Phase 1），单一 head。
- 新表：`employment_feedback_batches`、`employment_facts`、`employment_fact_matches`、
  `integration_api_keys`、`integration_nonces`。
- 既有表变更：`audit_logs.user_id` 由 NOT NULL 放宽为可空（见下）。
- 无回填：存量数据不得自动伪造真实入离职时间（§16）。

## 对计划的偏离（均以设计文档为准，已在提交信息中逐条记录）

1. **重复文件防护索引失效**（`a48d8c9`）。计划的 `ux_batch_confirmed_file` 谓词为
   `status = 'confirmed'`，但 `confirm_import()` 最终把批次落在 `imported_pending_calculation`，
   事务提交后谓词匹配不到任何行，§6.1「同一文件不得重复确认」将由空气保证。已改为覆盖
   全部确认后状态；`failed`/`rejected` 排除在外，使失败的导入可用同一文件重试。
2. **原始文件无处存放**（`e263264`）。计划要求确认时重算报告而不信任客户端，但表结构里
   没有任何字段存原始文件。§6.4 本就要求「原始上传文件必须私有、加密并设置保留期限」，
   故批次新增 `source_file_path`，文件 Fernet 加密存于 web 根之外。内存缓存无法跨重启或多
   worker，不可接受。
3. **导入事实状态**（`e263264`）。计划的冒烟断言导入后事实为 `active`，但无对应在保人员时
   必须停在 `pending_match`，否则违反 §20.6（`active_facts` 正是按状态过滤）。测试改为
   匹配case 补种人员、未匹配case 断言 `pending_match`。
4. **接入密钥存储自相矛盾**（`111ceb4`）。计划同时要求「passlib 哈希存储」与「用密钥重算
   HMAC」——单向哈希无法重算 HMAC。§7.3 允许「API Key 或签名认证」，保留签名方案（密钥不
   上线路），密钥改为 Fernet 加密静态存储，列名 `secret_hash` → `secret_cipher` 以名副其实。
5. **机器调用审计导致 500**（`111ceb4`）。§7.3 要求完整审计，但 `audit_logs.user_id` 为
   NOT NULL 而机器调用无 users 行。已放宽为可空（只放松约束、向后兼容、审计表只追加），
   机器调用以 `user_id IS NULL` + detail 中的 key_id 标识。
6. 越权写入原被当作行级格式错误返回 400，已改为范围先行校验，返回 403。
7. 计划示例身份证号过不了自身的 GB 11643 校验；测试改用合法号码。
8. 小出入：计划预期 404 实为 405（`app.py` 的 SPA catch-all 只挂 GET）；
   `verify_production_config()` 实际名为 `_check_production_config()`。

## 已确认的设计决策

匹配阶梯第 ② 级（身份证哈希）打到 `InsuredPerson`，①③ 级走历史 `EmploymentFact`
（`InsuredPerson` 上没有外部用工记录号与员工编号字段）。**已于实施前与用户确认**，
因为该选择决定事实如何绑定到人，绑错会让 Phase 3 对该人的全部指标失真。
`InsuredPerson.id_number` 为明文且无哈希列，故第 ② 级在企业+单位范围内取候选后现算哈希
比对，属范围内扫描而非索引查找；若日后成为瓶颈，可给 `InsuredPerson` 增加哈希列。

## 验证（2026-07-17，均在最终提交状态上执行）

- `[x]` `id_number_test`、`employment_model_test`、`employment_fact_service_test`
- `[x]` `employment_matching_test`、`employment_import_test`、`employment_integration_test`
- `[x]` `employment_facts_smoke`
- `[x]` 既有回归：`employer_scope_smoke`、`security_smoke`、`system_smoke`、
  `recharge_smoke`、`participation_lock_smoke`、`salesperson_portal_smoke`、
  `agent_pricing_visibility_test`
- `[x]` `python3 -m compileall -q backend`、`git diff --check`
- `[x]` `alembic heads` 单一 head `c40dab695a66`
- `[x]` 迁移在线双向：SQLite 上 `downgrade` 干净删表、`upgrade` 重建表与索引
- `[x]` 离线 SQL：SQLite 与 PostgreSQL 均生成，部分唯一索引 `WHERE` 子句正确
- `[x]` 空库初始化（30 表）与旧库补齐（25 表 → 补齐 5 张新表）
- `[ ]` Web 构建与 Maven：本阶段未改前端与 Java，按计划不要求；Java 镜像属 Phase 6。
- `[ ]` 生产 PostgreSQL 实测：本地无实例，仅验证离线 SQL。

## 部署前置（需用户操作，阻断性）

**`ID_ENCRYPTION_KEY` 必须在本阶段部署前由用户在 Render 后台设好，否则生产启动直接失败**
（`_check_production_config()` 的 fail-fast，设计要求）。`render.yaml` 已声明该 key 为
`sync: false`，但代理不得设置生产密钥。

该 key **一经设定不可轮换**：轮换会使已加密的身份证与原始导入文件全部无法解密。

新增依赖 `cryptography` 已写入 `requirements.txt`。

## 已知风险

- 旧 SQLite 空库纯 Alembic 全链升级仍在既有迁移 `96b709380f70` 处失败（SQLite 不支持
  ALTER 约束）。该问题早于本阶段，与 `c40dab695a66` 无关，未在本阶段修复；生产 PostgreSQL
  走线性迁移不受影响。
- 外部接入密钥目前只能由运维直接写库签发，没有管理界面（本阶段范围外）。
- §7.3 提到的限流未实现（计划未列入本阶段任务）。
- 原始导入文件按 §6.4 加密留存，但**保留期限清理尚未实现**，需后续阶段补。
- 事实的 `pending_match` 队列有 API（`/employment-facts/unmatched`）但无界面，Phase 4 负责。

## 合并

- 合并提交：`b314cbc`（`Merge branch 'feat/employment-facts-phase2'`），无冲突。
- 合并后在 `main` 上重跑全量门槛：14 个测试文件、`compileall`、单一 head `c40dab695a66` 全部通过。
- **未推送、未部署**：`render.yaml` 的 `autoDeployTrigger: commit` 会使推送即自动部署，
  而 `ID_ENCRYPTION_KEY` 尚未在 Render 配置，届时 `_check_production_config()` 会阻断启动，
  造成生产不可用。必须先配好该密钥再推送。

## 下一动作

- 用户在 Render 配置 `ID_ENCRYPTION_KEY` 后方可推送发布。
- 迁移锁已释放，Phase 3（及时率引擎）可基于 `main@b314cbc` 创建新分支与迁移，
  新迁移须线性接在 `c40dab695a66` 之后。
- Phase 3 消费的接口：`active_facts`、`correct_fact`、`serialize_fact`、
  `FACT_EXCLUDED_STATUSES`（`backend/services/employment_facts.py`），
  并负责把批次从 `imported_pending_calculation` 推进到 `completed`。
