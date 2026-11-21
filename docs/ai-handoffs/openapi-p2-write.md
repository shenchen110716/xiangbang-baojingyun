# 任务交接：开放平台 P2 写入接口（story-p2-write）

- **状态**：done（代码与测试全部完成；按编排红线**未 commit / 未 push / 未切分支**，工作树落在 `feature/openapi-platform` 上，由编排方验收后提交）
- **仓库**：`blueprints/xiangwuyou/Demo`，分支 `feature/openapi-platform`（P0=fbd176a、P1=dfe53ca 已在位）
- **规格**：`docs/stories/story-p2-write.md`（AC1–AC19）
- **执行**：orchestrate run r-20261119-openapi-platform / step 7

## 本期落地范围

/openapi/v1 写入面三组能力，全部挂 P0/P1 原语（OpenApiRoute 统一包、
签名 v2 鉴权、scope、限流、请求日志）：

1. **参停保申报**（`routers/openapi/declarations.py` + `services/openapi/declarations.py`）
   - `POST /enrollments`、`POST /terminations`（强制 Idempotency-Key）、
     `GET /declarations`、`GET /declarations/{id}`。
   - 受理模型：整单校验（空单 / >500 / 余额门禁 / 岗位归属）→ 422 不产单；
     逐人受理即拒（证件类型 / 格式 / 未满 16 / 姓名矛盾 / 重复在保 / 批内重复）；
     受理项复用既有 `_enroll_person` 语义建档为 pending。
   - 终态由**人工确认**驱动：`routers/insured.py::insured_status` 在 commit 前调
     `on_insured_status_changed`，翻申报项终态并 emit `insured.activated/stopped`、
     `declaration.completed`。
   - 申报限流独立 bucket：60 次/小时/key（`OPENAPI_DECLARATION_RATE_LIMIT_PER_HOUR`
     可调），参保/停保共享，429 + Retry-After。
2. **充值**（`routers/openapi/recharges.py`）
   - `POST /recharges`（wechat_native 仅 usage；bank_transfer 走
     RechargeRequest + 回单上传 `POST /recharges/{id}/receipt` 原始字节 +
     filename 查询参数）、`GET /recharges/{recharge_id}`（`wechat-`/`bank-` 前缀）。
   - 微信回调沿用 `/api/payments/wechat-notify`（mock 验签）；银行面 confirm/
     reject 复用 `routers/recharge_requests.py`，在 commit 前 emit
     `recharge.succeeded/rejected`。
3. **Webhook**（`routers/openapi/webhooks.py` + `services/openapi/webhook_outbox.py`）
   - 注册（https 强制、loopback http 放行、每企业 ≤10）/ 列表 / 删除 /
     `POST /webhooks/{id}/test`。
   - Outbox：`emit_event` 与业务同事务落 `openapi_webhook_deliveries`；
     后台 tick 投递（urllib POST 5s 超时、每 tick ≤20 条、逐条独立 commit）；
     退避 1m/5m/30m/2h/12h，5 次后 failed；投递头 = v2 签名 + X-Event-Id，
     签名密钥 = endpoint 所属 key 的 AppSecret；payload 无明文证件号。
   - 平台侧手动重发：`routers/openapi_admin.py::resend_openapi_webhook_delivery`、
     `list_openapi_webhook_deliveries`。
4. **幂等**（`services/openapi/idempotency.py`）：claim-first + 唯一索引裁决；
   缺头/超长 422、同体回放、异体 409、处理中 409、业务失败 release、7 天清理。
5. **余额预警**（`services/openapi/webhook_outbox.py::scan_balance_alerts`
   注册为 `openapi_balance_alerts` job）：`enterprises.usage_alert_threshold` 非空企业，
   available 跌破阈值 emit 一次 `balance.low`，回升后重新武装；状态持久化在
   AuditLog(action="openapi_balance_low")。

## 迁移

- 新 Alembic 迁移 head：`d8e3f6a2b9c4`（down_revision=`c7d2e9f4a1b8`，单一 head）。
  新表 5 张：`openapi_declarations`、`openapi_declaration_items`、
  `openapi_webhook_endpoints`、`openapi_webhook_deliveries`、
  `openapi_idempotency_keys`；`enterprises` 加 `usage_alert_threshold`。
- SQLite 运行时桥 `backend/core/migrations.py` 已同步。
- ⚠️ **PG 验证未做**（Pod 无 Neon 凭据）：合并前须由编排方在 VPS/临时库跑
  `python3 scripts/pg_migration_check.py`（项目协作规则的硬门槛）。

## 本期发现并修复的缺陷

- **declarations 卡 processing（实现 bug，写 smoke 抓出）**：`SessionLocal`
  是 `autoflush=False`，`_close_pending_items` 改完 item 状态后
  `_maybe_finish_declaration` 直接 SELECT 仍读到旧的 pending，申报单永远
  不翻终态、`declaration.completed` 永远不发。修复：`_maybe_finish_declaration`
  进门先 `session.flush()`（`services/openapi/declarations.py`）。
- review-p1 n1（Minor）：`routers/openapi/insured.py::get_export_task` 对
  `params_json` 合法 JSON 但非对象的情况按最严格（明文）口径处理。
- **P0/P1 迁移 smoke 对新 head 的适配**（预期内的小更新，非回归）：
  `tests/openapi_foundation_smoke.py` 的隔离迁移测试补了 `enterprises`
  最小桩（P2 迁移要 ALTER 它）；`tests/openapi_readonly_smoke.py` 不再钉
  head 的具体修订号，只钉「单一 head」不变量。

## 设计决策（含风险）

- **全部项受理即拒 → 申报单直接 `failed`，不发 `declaration.completed`**：
  该事件只由确认钩子触发；文档 §5 已写明「全拒直接 failed，请以 GET 申报单
  状态为准」。若对接方强依赖事件，后续可在受理路径补 emit。
- **Idempotency-Key 只强制在三个创建端点**（enrollments/terminations/recharges）；
  回单上传与 webhook 管理天然幂等/低危，不要求。
- **余额预警状态存 AuditLog**（最新 low/recovered 行即状态），不加新表；
  默认视为 recovered。
- **机器申报 `ParticipationOperation.submitted_by = NULL`**，审计走
  openapi request log + key_id。
- **银行充值 `created_by` 回填**：RechargeRequest.created_by NOT NULL，
  openapi 面取企业绑定用户回填（`_fallback_created_by`）。
- **事件静默丢弃**：企业无 active endpoint 时 `emit_event` 返回 None 不落行——
  对接方必须先注册 webhook 再申报，文档已写明。
- 文档中删除了 `policy.issued` 事件（全仓无 emit，避免「逐项一致」失真）。

## 文档 / 对接物料（AC18）

- `docs/open-api-usage-guide.md`：banner、§1 能力表、§4 幂等与限流、§5 申报
  （状态机 + 整单 422 清单 + 逐人拒绝清单）、§7.2 充值、§9 事件字典/签名/退避
  全部与实现逐项对齐（web 文档页 `?raw` 内联，已随 `npm run build` 验证）。
- `docs/open-api-postman-collection.json`：补 12 个写入端点请求（含
  Idempotency-Key 预填 `{{$guid}}`；回单 binary 上传在 Postman 里无法预计算
  签名，已在请求说明中明示）。

## 测试门槛（命令与真实 exit code 见步骤汇报）

- 新增 `tests/openapi_write_smoke.py`：隔离临时 SQLite + 本地 HTTP 接收端，
  覆盖 AC1(本地迁移)–AC17：各写端点成功路径、幂等全口径、受理即拒全分支、
  状态机、充值双通道、webhook 注册/投递/签名正负例/退避/手动重发、余额预警、
  申报限流、跨租户与 scope 负例、幂等键清理。
- 回归：openapi_readonly_smoke、openapi_foundation_smoke、system_smoke、
  security_smoke、participation_lock_smoke、recharge_smoke、
  enroll_payment_test、insured_draft_enroll_test；`python3 -m compileall -q
  backend`；`git diff --check`；`python3 -m alembic heads`（单 head）；
  `cd web && npm run build`。

## 未尽事项

1. PG 迁移验证（见上，编排方执行）。
2. commit/push/PR 由编排方按验收流程做（本步骤红线禁 commit）。
3. `miniprogram/` 的脏状态是 run 前残留，本任务未触碰。

---

## 2026-11-22 rework（review-p2 变更请求，attempt 2）

按 `docs/stories/review-p2.md` 的 B1 + M1–M7 + minors 全部返工，均在
`feature/openapi-platform` 未提交工作树内（红线不变：未 commit）。

### 修复清单（位置）

- **B1 事件循环阻塞**：`backend/services/openapi/background.py`
  `background_loop` 中 `run_locked_tick()` 改为
  `await asyncio.to_thread(run_locked_tick)`（tick 内含同步 DB + 最多
  20 条 × 5s 超时的出网 webhook 投递，直接跑会卡死事件循环 ~100s）。
  新增测试 `test_background_loop_nonblocking`：注册 1s 慢 job，断言
  事件循环心跳间隔 < 0.5s。
- **M1 跨租户姓名回显**：`backend/services/openapi/declarations.py`
  `_reject_reason_for_enrollment` 的姓名-证件不一致检查加
  `enterprise_id == 请求方企业` 过滤，拒绝文案改为固定话术
  「该证件号与本企业已登记的姓名不一致，请核对后重新提交」，不再回显
  系统已登记姓名。
- **M2 状态翻转绕过 AC6 钩子**：新增唯一入口
  `apply_person_status(session, person, new_status, *, enforce_transition)`
  （`backend/services/openapi/declarations.py`，钩子所在模块；
  `assert_status_transition` 函数内延迟 import 防循环）。接入 6 处：
  `routers/insured.py` 新人建档成员纠正、日期修正复保/停保、
  `insured_status` 翻转、import 建档、import 停保；
  `routers/pending_terminations.py` 批量确认停保。全部
  `enforce_transition=False` 保持既有管理端语义（旧测试全绿为证）。
- **M3 幂等快照与副作用单事务**：service 层
  （`services/openapi/declarations.py` 两个 accept_*、
  `routers/openapi/recharges.py` `_create_recharge` 两通道）只
  `session.flush()` 不 commit；`IdempotencyContext.save` 的
  `session.commit()` 是整条写请求唯一一次提交。新增崩溃窗口测试
  `test_idempotency_crash_window`（monkeypatch save 抛 RuntimeError →
  500/50000、无半成品落库、同 key 重试成功）。
  **返工中发现的连带必改**：路由 except 块必须 `session.rollback()`
  之后再 `ctx.release()`——M3 之后业务会话在失败点还持有未提交写事务
  （SQLite 下占库级写锁），release 的独立会话 DELETE 会被锁住且被
  release 的异常吞掉，占位行残留 → 同 key 永久 409。已改三处：
  `routers/openapi/declarations.py` 两个 POST、
  `routers/openapi/recharges.py` `create_recharge`。
- **M4 webhook 重复注册**：`routers/openapi/webhooks.py`
  `register_webhook` 按 (enterprise_id, url) 去重，重复注册返回既有
  endpoint（幂等 200），置于 10 个上限检查之前；usage-guide §9 已写明。
- **M5 预警阈值管理入口**：`schemas/enterprise.py` `EnterpriseUpdate`
  加 `usage_alert_threshold`（≥0 校验）；
  `web/src/views/enterprises/EnterprisesPanel.vue` 编辑表单加
  「开放平台预警阈值」数字输入（仅编辑态；创建 schema 无此字段）+
  `web/src/api/types.ts` Enterprise 类型；usage-guide §7 改为
  「管理端『参保单位 → 编辑』配置」。smoke 加
  `test_usage_alert_threshold_admin_entry` 直调 `update_enterprise`。
  **已知限制**：`update_enterprise` 的 PATCH 循环跳过 None 值，阈值
  **无法经 API 清空**（UI 留空=不变）；清空需 ORM/SQL 直改，后续如需
  可为该字段单独放行 None。
- **M6 webhook URL 口径**：`services/openapi/webhook_outbox.py`
  `is_acceptable_webhook_url`——https 且公网才受理；https 指向
  私网/环回/链路本地/保留段（10/172.16/192.168/127/169.254/::1/
  localhost 等，`ipaddress.is_global` 判定，域名不做 DNS 解析视为公网）
  → 422；http 仅当部署侧设 `OPENAPI_WEBHOOK_ALLOW_INSECURE=1` 且主机
  为环回才受理（默认关闭，测试自开）。smoke 覆盖 7 个私网负例 +
  生产姿态（开关关掉后 http 环回 422）。
- **M7**：即本节。
- **minors**：m1 停保目标优先取在保行（`_find_termination_target`）；
  m2 无 endpoint 不武装 balance.low（`scan_balance_alerts` 检查
  `emit_event` 返回 None 则不落 AuditLog，smoke 断言）；m3
  `_gate_balance` 非 403 的 HTTPException 原样 re-raise；m5 管理端
  手动重发仅限 failed 状态（`routers/openapi_admin.py`）。

### 记录未做（record-only，报告评审知悉）

- **m4 批量申报逐人查询未合批**：500 人申报 = 逐人 2~3 条 SELECT。
  SQLite/当前量级可接受；若量级上来，可按 id_number/external_id 批量
  预取。不在本次返工范围。
- **m6 投递循环租户公平性**：每轮 tick 最多 20 条按 id 序投递，单一
  企业大量积压可能饿到其他企业。可加 per-enterprise 轮转；暂记录。

### 返工后门槛（真实 exit code 见步骤汇报）

openapi_write_smoke（含 5 个新测试）、openapi_readonly / foundation、
system / security / participation_lock / recharge / enroll_payment /
insured_draft_enroll、compileall、`git diff --check`、alembic 单 head
`d8e3f6a2b9c4`、`cd web && npm run build`（M5 改了前端，必跑）。
