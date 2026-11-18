# 无忧保开放平台（Open API）完整实现设计方案

> 2026-11-18 · 基于当前生产代码（FastAPI backend v3.6.0，bx.xbbzp.com）的实勘结果设计。
> 目标：把无忧保做成公共平台，向企业客户开放 参保/停保申报、缴费、保单下载、参保人员名单 等 API。

## 0. 设计原则（先说结论）

1. **建在 FastAPI 单体内，不另起服务。** Java 后端自 2026-07-25 起停更、定位是只读运行时镜像（见 `java-backend` 的 phase6 交接文档），生产流量全在 Python。开放平台做成新的路由层 `/openapi/v1/**`，通过防腐层复用既有 services，不碰 `/api`（后台 SPA 与小程序专用）。
2. **复用既有 HMAC 签名机制，不引入 OAuth2。** `core/api_keys.py` 已实现 HMAC-SHA256 签名 + nonce 防重放 + Fernet 加密存储密钥，`routers/integrations.py` 的用工事件接口在生产模式下已验证可用。对 server-to-server 场景这比 OAuth2 client_credentials 更简单、更难被误用，只需补齐签名串、密钥管理和配套。
3. **贴着业务现状建模。** 缴费 = 预付费余额（保费户 `premium_balance` / 服务费户 `usage_balance`），所以开放的是「充值/余额/流水」而非「账单支付」；参停保落地后仍需保司与运营人工确认，所以对外必须是「申报单受理 → 异步回执」模型，不能承诺同步生效。
4. **开放平台上线前必须补两块安全短板**：身份证号明文存储/导出（个保法敏感个人信息，硬伤）；进程内内存限流在多 worker 下无效。

## 1. 总体架构

```
企业客户系统（ERP/HR/用工平台）
        │  HTTPS + HMAC 签名
        ▼
/openapi/v1/**  ← 开放 API 路由层（新增 routers/openapi/）
   ├─ 认证与鉴权：API Key 签名校验、scopes、IP 白名单
   ├─ 公共管线：限流、幂等、审计、统一响应/错误码、request_id
   ▼
防腐层 services/openapi/*.py（新增，只编排不写业务规则）
   ▼
既有业务层（原样复用）
   ├─ services/policy_members.py   activate/terminate
   ├─ services/participation_lock.py  余额闸口
   ├─ providers.py                 微信支付 v3
   ├─ core/storage.py / file_tokens.py  保单文件与签名下载
   └─ models/*（InsuredPerson、PolicyMember、PaymentRecord、Policy…）
```

**一条必须写进代码评审清单的红线**：现有权限过滤是「负向」的——`role == enterprise` 才过滤 `enterprise_id`，其他角色全量可见（`core/security.py:13-20` 自己都标注了这个坑）。开放 API 的主体 `api_client` **绝不允许落进这个分支逻辑**：openapi 层每条查询显式 `WHERE enterprise_id = key.enterprise_id`，并为「API key 只能看到自己企业的数据」写专门的越权测试。

## 2. 接入与认证

### 2.1 应用（密钥）管理

把现有 `IntegrationApiKey`（`models/employment.py:143`）升级为通用的 `OpenApiClient`（可以复用同表加列，也可新表迁移数据，建议后者，语义干净）：

| 字段 | 说明 |
|---|---|
| `key_id` / `secret_encrypted` | AppId + Fernet 加密的 AppSecret（沿用现机制） |
| `enterprise_id` | 绑定投保单位，一企业可多 key |
| `scopes` | 权限范围 CSV/JSON，见 2.3 |
| `allowed_employer_ids` | 可选，限定实际工作单位范围（沿用现字段） |
| `ip_allowlist` | 可选 CIDR 列表 |
| `status` / `expires_at` | active/disabled，支持到期 |
| `secondary_secret_encrypted` + `rotated_at` | **双密钥轮换**：轮换期新旧密钥同时有效 7 天 |
| `env` | `prod` / `sandbox` |

**现状缺口**：目前没有任何端点或界面签发密钥（只有测试代码会造）。需在管理后台的企业详情页新增「开放平台」页签：签发/禁用/轮换密钥（Secret 仅创建时明文显示一次）、配置 scopes 与 IP 白名单、查看调用日志。对应新增 `routers/openapi_admin.py`（挂在 `/api` 下，admin 角色）。

### 2.2 请求签名（在现有基础上升级）

现有签名串是 `ts\nnonce\nsha256(body)`——**没有把 method 和 path 签进去**，同一签名可被重放到其他端点（现在只有一个端点所以没暴露）。开放平台升级为：

```
StringToSign = METHOD \n PATH \n sorted_query \n X-Timestamp \n X-Nonce \n sha256(body)
X-Signature  = HMAC-SHA256(AppSecret, StringToSign)
```

- 请求头沿用：`X-Api-Key-Id`、`X-Timestamp`（±300s 时钟偏移）、`X-Nonce`、`X-Signature`。
- 防重放沿用 `IntegrationNonce` 的 (key_id, nonce) 唯一索引；**补一个每日清理任务**（现状该表只增不减）。
- 版本兼容：签名算法标记 `X-Sign-Version: 2`，旧用工事件接口继续认 v1。

### 2.3 权限范围（scopes）

```
insured:read            名单/状态查询（身份证号脱敏）
insured:read_sensitive  含明文证件号（需企业签数据安全承诺后人工开通）
declaration:write       参保/停保申报
account:read            余额、流水、发票
recharge:write          发起充值
policy:read             保单列表、详情、下载
webhook:manage          回调地址管理
```

### 2.4 沙箱

`env=sandbox` 的密钥路由到沙箱企业（独立 `enterprise_id`，预置余额与假数据，微信支付走现有 mock provider）。对接方不碰真实数据即可完成联调。

## 3. API 设计

### 3.0 通用约定

- 统一响应：`{"code": 0, "message": "ok", "data": …, "request_id": "…"}`；错误码分段（40100 签名类 / 40300 权限类 / 42200 业务校验 / 42900 限流 / 50000 系统）。
- 分页：游标式 `?cursor=&limit=`（默认 50，上限 200），响应带 `next_cursor`。
- **所有写接口必带 `Idempotency-Key` 头**：新表 `openapi_idempotency_keys(key_id, idem_key, request_hash, response_snapshot, created_at)` 唯一索引兜底，同 key 同请求返回快照，同 key 不同请求体返回 409。
- 增量同步：列表接口支持 `updated_since` 时间戳。

### 3.1 参保/停保（异步申报单模型）

现状是同步写库 + 管理员人工确认 + 邮件发保司（mock provider），**不能对外承诺同步生效**。对外抽象为「申报单」：

```
POST /openapi/v1/enrollments        批量参保申报（≤500 人/单）
POST /openapi/v1/terminations       批量停保申报
GET  /openapi/v1/declarations/{id}  申报单状态 + 逐人明细
GET  /openapi/v1/declarations       申报单列表
```

参保申报项字段：姓名、证件类型+号码、手机号、岗位（`position_id` 或岗位编码）、方案（`plan_id`）、期望生效日期、可选 `external_person_id`（对接方自己的员工 ID，原样回传）。

**申报单状态机**（新表 `openapi_declarations` + `openapi_declaration_items`）：

```
accepted（受理，同步返回）
  → processing（已落库为 InsuredPerson draft→pending，走现有 batch-enroll 流程）
  → succeeded / partial_succeeded / failed（终态，逐人带 reason）
```

- 受理时同步做的校验并即时拒绝：证件号格式校验（复用 `is_valid_id_number`）、重复在保检测、**服务费余额闸口**（复用 `services/participation_lock.py` 的 `require_usage_funded`，余额不足整单 422 拒绝并提示先充值）。
- 落库复用现有链路：`InsuredPerson`(draft) → batch-enroll(pending) → 运营确认后 `activate_person_policy` 翻转 active，同时写 `ParticipationOperation` 审计。停保同理走 `terminate_person_policy`。
- **终态翻转点**：在现有管理员确认动作（`PATCH /api/insured/{id}/status` 等）里埋钩子，更新申报项状态并触发 webhook。对外 SLA 承诺「T+1 工作日回执」，与运营实际节奏对齐。
- 幂等：`Idempotency-Key` + 申报项内 (证件号, type, 生效日期) 去重，思路沿用 `employment-events` 的 `source_event_id`。

### 3.2 参保人员名单

```
GET  /openapi/v1/insured-persons?status=&position_id=&updated_since=&cursor=
GET  /openapi/v1/insured-persons/{id}            含当前 PolicyMember 的保障期间
GET  /openapi/v1/insured-persons/{id}/history    参停保操作历史（ParticipationOperation）
POST /openapi/v1/insured-persons/export          异步导出 XLSX → 返回 task_id
GET  /openapi/v1/export-tasks/{id}               完成后给短时效签名下载链接
```

- 状态映射对外语义化：`draft`→未参保、`pending`→待生效、`active`→在保、`stopped`→已停保。
- **身份证号默认脱敏**（`110***********1234`），`insured:read_sensitive` scope 才回明文；导出文件同规则。

### 3.3 缴费（预付费余额模型）

```
GET  /openapi/v1/accounts                余额：保费户 premium_balance / 服务费户 usage_balance、日服务费
POST /openapi/v1/recharges               发起充值 {account, amount, channel}
GET  /openapi/v1/recharges/{id}          充值单状态
GET  /openapi/v1/ledger-entries?account=&from=&to=&cursor=   收支流水
GET  /openapi/v1/invoices                发票记录
```

- `channel=wechat_native`：复用 `providers.py` 的微信 v3 native 下单，返回 `code_url`（企业财务扫码）；支付回调沿用现有 `/payments/wechat-notify` 验签链路，成功后发 webhook。
- `channel=bank_transfer`：返回收款账户信息，创建 `RechargeRequest`（pending），支持 `POST /recharges/{id}/receipt` 上传回单；运营在现有 `recharge_requests` 后台确认后发 webhook。
- 余额预警：企业可设阈值，低于阈值发 `balance.low` 事件（配合参保闸口，避免申报被拒）。

### 3.4 保单

```
GET  /openapi/v1/policies                    本企业相关保单（policy_no、起止、状态）
GET  /openapi/v1/policies/{id}
POST /openapi/v1/policies/{id}/download-url  返回短时效签名 URL（300s）
GET  /openapi/v1/policies/{id}/members       该保单下本企业在保人员
```

- 下载完全复用现有机制：本地存储走 `core/file_tokens.py` 的 HMAC 短时效链接，S3/R2 走 presigned URL（`core/storage.py`）。**只开 download-url 间接模式，不直接回文件流**，避免把大文件传输压在应用进程上。
- 权限注意：保单可能覆盖多家企业，`members` 必须按 `enterprise_id` 过滤后返回（又是 1 节说的红线）。

### 3.5 Webhook（事件回调）

事件：`declaration.completed`、`insured.activated`、`insured.stopped`、`recharge.succeeded`、`recharge.rejected`、`policy.issued`、`balance.low`。

```
POST   /openapi/v1/webhooks        注册回调地址（https 必须）
GET    /openapi/v1/webhooks
DELETE /openapi/v1/webhooks/{id}
POST   /openapi/v1/webhooks/{id}/test   发测试事件
```

- **Outbox 投递**（现成先例：`TimelinessOutbox`）：业务事务内写 `openapi_webhook_deliveries(event_type, payload, status, attempts, next_retry_at)`，后台任务投递；指数退避重试 5 次（1m/5m/30m/2h/12h），失败可在后台手动重发。
- 推送带同款 HMAC 签名头（用该企业的 AppSecret），对方验签；payload 只带 ID 和关键字段，敏感明细让对方回查 API（减少敏感数据出站面）。
- 对方响应 2xx 即成功；要求幂等消费（带 `event_id`）。

## 4. 新增基础设施：一个统一的后台任务循环

现状全项目没有调度器（`policy_members.py:173`、`timeliness_recalc.py:365` 都注明 no scheduler）。开放平台需要的后台工作统一收进**一个** asyncio 周期任务（FastAPI lifespan 启动，30s tick）：

1. webhook outbox 投递与重试；
2. `IntegrationNonce` / 幂等键 / 请求日志的过期清理；
3. 异步导出任务执行；
4. 余额阈值扫描（`balance.low`）。

多 worker 部署下用 **PostgreSQL advisory lock**（`pg_try_advisory_lock`）保证单实例执行，不引入 Redis/Celery——与单 VPS、多产品共存的运维现实匹配（VPS 磁盘紧张，少装一个组件是一个）。

## 5. 限流、审计与安全加固

- **限流**：现有 `routers/enroll.py:33` 式进程内字典多 worker 下无效。新表 `openapi_rate_counters(key_id, window_start, count)` 做 PG 固定窗口计数（默认 10 QPS/key、批量申报 60 次/小时、导出 10 次/小时），超限 429 + `Retry-After`。量级（企业 server-to-server，QPS 个位数）PG 完全扛得住，将来真有高频客户再上 Redis。
- **审计**：每次调用写 `openapi_request_logs(request_id, key_id, enterprise_id, method, path, status, latency, ip)`，写操作同时落既有 `audit_logs`；保留 180 天。管理后台可按企业/key 查询——也是将来按调用量计费的计量基础。
- **身份证加密迁移（上线前置项）**：`InsuredPerson.id_number` 现为明文 `String(40)`，且 XLSX 导出、保司邮件全明文。对齐 `EmploymentFact` 已有的 `core/id_number.py` 方案（HMAC 哈希列用于查重/检索 + Fernet 密文列），alembic 分三步：加列→双写回填→删明文列。导出与邮件默认脱敏，明文导出收敛到显式授权动作。**这是存量合规债，不只为开放平台，但开放平台把数据出口放大了，必须先还。**
- 其余：仅 HTTPS；CORS 对 `/openapi` 不开放（server-to-server 不需要）；错误响应不回显内部细节；密钥泄露应急流程 = 后台一键禁用 + 签发新钥。

## 6. 数据模型变更汇总（alembic）

| 新表 | 用途 |
|---|---|
| `openapi_clients` | 应用密钥（从 `integration_api_keys` 迁移数据） |
| `openapi_declarations` / `openapi_declaration_items` | 申报单及明细 |
| `openapi_webhook_endpoints` / `openapi_webhook_deliveries` | 回调配置与 outbox |
| `openapi_idempotency_keys` | 写接口幂等 |
| `openapi_request_logs` | 调用日志/计量 |
| `openapi_rate_counters` | 限流窗口 |
| `export_tasks` | 异步导出 |

另：`integration_nonces` 加 `created_at` 索引供清理；`insured_people` 身份证加密迁移（独立做）。
按项目规则：每个迁移先过 `scripts/pg_migration_check.py` 跑真实 PostgreSQL 验证；auth/RBAC/billing/迁移类改动串行进行，不并行。

## 7. 对接配套

- **开发者文档**：`/openapi/v1` 的 FastAPI 自动 OpenAPI schema 单独暴露（与内部 `/api` 的 docs 分开），加一页静态「接入指南」：签名算法图解 + Python/Java/PHP/Node 四语言签名示例 + Postman 集合 + 错误码表 + 事件字典。
- **联调三件套**：沙箱密钥、`POST /webhooks/{id}/test`、后台调用日志自助排错。
- **计费预留**：`openapi_request_logs` 即计量数据，未来可按「免费额度 + 超量阶梯」出账，先不做结算功能。

## 8. 实施路线（四期，均可独立上线）

| 期 | 内容 | 规模估计 |
|---|---|---|
| **P0 地基** | `openapi_clients` + 后台签发页、签名 v2、PG 限流、审计日志、统一响应/错误码、后台任务循环（含 nonce 清理）、沙箱机制 | ~5 人日 |
| **P1 只读** | 名单查询/历史、保单列表+下载链接、余额/流水/发票、异步导出；文档站初版 | ~5 人日 |
| **P2 写入** | 参保/停保申报单全链路（含运营确认钩子）、充值（微信 native + 银行转账）、幂等、webhook 全套 | ~8 人日 |
| **P3 合规硬化** | 身份证加密迁移 + 全出口脱敏收敛、密钥轮换、渗透自查、接入首个真实企业试点 | ~5 人日 |

P0+P1 先行可以让首批企业把「查名单、下保单、看余额」跑起来建立信任，P2 的写链路涉及钱和保司流程，放在有真实反馈之后。

## 9. 风险与开放问题

1. **申报终态时效取决于人工运营**（保司确认是管理员手点，`submit_enrollment` 是 mock）。短期用「T+1 回执」SLA 兜底；中期如与保司打通系统对接，申报单状态机无需改动，只换终态触发源。
2. **状态机目前无转移校验**（`policy_members.py:82` 自述）。开放 API 把状态翻转暴露给外部触发链后，必须先补非法转移防护，纳入 P2。
3. **部署分支陷阱**：本仓库推送用裸 `git push`（走 `origin/wuyoubao-archive`），严禁 `git push origin main`。
4. **要不要 OAuth2**：不要。HMAC 方案已有生产验证，server-to-server 够用；若未来有「企业用户授权第三方 SaaS 代操作」的场景再引入。
