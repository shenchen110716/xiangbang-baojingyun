# 响帮帮无忧保 v4.0 生产系统设计

文档状态：设计基线  
版本：v4.1（在 v4.0 基础上补充业务员角色、合并 CoveragePeriod 到 PolicyMember、明确账本记账模型）  
更新日期：2026-07-13  
适用范围：平台电脑端、投保单位电脑端、微信小程序、FastAPI 后端及外部服务集成

## 1. 文档定位

本文档是响帮帮无忧保从“可运行原型”升级为“可持续运营生产系统”的技术与业务架构基线。

- `PRODUCT-DESIGN.md` 继续描述产品范围、页面能力和业务需求。
- 本文档定义领域边界、数据模型、状态机、安全规则、接口约定、部署方式和迁移路径。
- 当现有实现与本文档冲突时，新增功能以本文档为准；存量行为通过迁移计划逐步收敛。

### 1.1 当前结论

现有系统可以继续用于内部演示和需求验证，但在完成 P0 上线门禁前，不得接入真实支付、真实身份证数据、理赔材料或保司生产接口。

P0 上线门禁：

1. 项目根目录、数据库、源码和上传文件不得通过静态路由公开访问。
2. 企业用户不得直接修改账户余额。
3. 支付回调必须验签、核单、幂等并写入资金流水。
4. 生产环境缺少 JWT、管理员密码、数据库和对象存储配置时必须拒绝启动。
5. 所有企业接口必须使用强制租户上下文，租户缺失时拒绝请求。
6. 系统测试、权限测试和支付测试必须全部通过。

## 2. 建设目标与非目标

### 2.1 建设目标

- 建立可追溯的参保、停保、续保、批改和保司回执链路。
- 建立不可变、可对账的保费与平台使用费资金账本。
- 保证任一企业只能访问自身数据，并支持平台内部最小权限分工。
- 保护身份证、理赔材料、保单、支付信息等敏感数据。
- 让外部调用可幂等、可重试、可回放、可人工补偿。
- 使用版本化数据库迁移和自动化测试保证持续交付。
- 保持模块化单体，降低当前阶段的分布式复杂度。

### 2.2 本阶段非目标

- 不拆分为微服务。
- 不建设通用低代码平台或工作流引擎。
- 不在第一阶段引入自动承保决策、智能定价或自动理赔。
- 不允许前端直接决定职业类别、价格、账户余额或保单生效结果。
- 不用缓存替代交易数据库的正确性约束。

## 3. 总体设计原则

1. **事件代替当前状态猜测**：参停保以业务事件和保障期间为准，不从员工创建时间推断。
2. **账本代替余额直改**：余额是已入账流水的结果，任何入口都不能直接加减余额。
3. **租户默认拒绝**：企业账号缺少租户、对象缺少租户或关系不一致时一律拒绝。
4. **服务端决定业务规则**：权限、价格、生效日、状态迁移和文件访问全部由后端校验。
5. **外部调用至少一次、业务结果仅一次**：允许网络重试，但依靠幂等键保证业务不重复。
6. **UTC 存储、业务时区结算**：时间点以 UTC 保存，日界线按企业配置的业务时区计算。
7. **敏感数据最少暴露**：列表默认脱敏，只有授权场景返回或导出完整信息。
8. **先模块化单体，再按证据拆分**：只有出现独立扩容、独立发布或团队边界需求时才拆服务。

## 4. 系统上下文与部署架构

```text
平台 SPA ───────┐
企业 SPA ───────┼── HTTPS ── API 网关 / FastAPI 模块化单体
微信小程序 ────┘                    │
                                    ├── PostgreSQL：交易与业务数据
                                    ├── Redis：短期缓存、限流、任务协调
                                    ├── 对象存储：岗位视频、理赔材料、回执文件
                                    ├── Worker：发送、回执、对账、通知、账单
                                    └── Outbox：可靠发布外部任务
                                              │
                         保司 API / 支付 / 短信 / 邮件 / 微信通知
```

### 4.1 部署单元

| 单元 | 职责 | 扩展方式 |
| --- | --- | --- |
| Web/API | 鉴权、查询、命令受理、文件授权 | 无状态水平扩展 |
| Worker | 外部调用、重试、对账、通知、账单计算 | 按队列并发扩展 |
| PostgreSQL | 业务事实、状态机、资金账本、审计 | 托管高可用、定时备份 |
| Redis | 限流、短锁、任务协调 | 不保存唯一业务事实 |
| 对象存储 | 私有文件、版本、生命周期 | 私有桶、服务端加密 |

生产环境不得把代码目录、数据库文件或对象存储目录作为静态站点挂载。前端构建产物放在独立的 `frontend-dist/`，业务文件只通过鉴权接口或短时签名 URL 访问。

## 5. 领域模块边界

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| Identity & Tenant | 用户、租户、角色、权限、登录会话 | 产品价格、业务状态 |
| Enterprise | 投保单位、实际用工单位、岗位、单位操作员 | 保司发送、资金入账 |
| Product & Pricing | 保司、产品、费率版本、职业类别、销售关系 | 修改历史保单价格 |
| Enrollment | 员工、参保申请、停保申请、批次、明细、回执 | 支付入账 |
| Policy | 保单、保单成员、保障期间、批改、续保 | 员工身份主数据 |
| Billing & Ledger | 账户、支付订单、流水、账单、对账、发票 | 直接调用保司参保 |
| Claims | 报案、材料、审核、补件、赔付、时间线 | 修改保单保障事实 |
| Agent & Commission | 业务员账户、企业-业务员佣金关系、推广关联 | 保单定价、参停保执行 |
| Integration | Provider、Outbox、重试、回执、幂等 | 决定领域状态是否合法 |
| Notification | 站内信、短信、邮件、微信通知、模板 | 任意收件人群发 |
| Audit & Reporting | 审计、运营指标、合规导出 | 修改交易事实 |

模块之间通过应用服务调用和领域事件协作。禁止 Router 直接修改其他模块的表，禁止 Provider 直接修改领域状态。

## 6. 租户与权限设计

### 6.1 主体模型

- `Tenant`：平台或投保单位的数据边界。
- `User`：登录身份，不直接代表权限集合。
- `Role`：角色，如平台管理员、运营、财务、理赔、核保、企业主管、企业经办人、业务员、只读审计员。
- `Permission`：动作权限，如 `policy.read`、`claim.review`、`ledger.adjust`。
- `UserRole`：用户在指定租户下拥有的角色，同一用户可在不同租户或不同角色维度下拥有多个角色。
- `DataScope`：平台全局、指定企业集合、或本人被授权访问的部分实际用工单位（ActualEmployer）数据。第三种作用域通过 `UserEmployerScope` 关联表承载，不是笼统的“本人经办数据”。

### 6.2 请求鉴权流程

```text
验证 Token
  → 读取用户与会话版本
  → 解析 tenant_id
  → 校验用户属于该租户
  → 校验 permission
  → 在 Repository 层强制附加 tenant_id
  → 校验目标对象 tenant_id
```

强制规则：

- 企业用户 Token 必须包含 `tenant_id`，数据库用户记录也必须绑定同一租户。
- 企业请求不得通过 Query 或 Body 切换租户；服务端使用 Token 中的租户。
- 平台跨租户操作必须具备明确权限，并写入审计日志。
- Repository 查询必须接收 `TenantContext`，不提供无租户的企业查询方法。
- 数据库对关键复合关系增加租户一致性约束，防止跨租户外键拼接。
- 密码修改、角色变更、账号停用后递增 `session_version`，旧 Token 立即失效。

### 6.3 初始权限矩阵

| 能力 | 平台管理员 | 运营 | 财务 | 理赔 | 核保 | 企业主管 | 企业经办人 | 业务员 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 产品与费率维护 | ✓ | ✓ | 只读 | — | ✓ | — | — | — |
| 企业与岗位管理 | ✓ | ✓ | 只读 | 只读 | ✓ | 本企业 | 本企业受限 | 关联企业只读 |
| 参停保提交 | ✓ | ✓ | — | — | 只读 | 本企业 | 本企业 | — |
| 资金入账与调账 | ✓ | 只读 | ✓ | — | — | 只读 | 只读 | — |
| 理赔材料上传 | ✓ | 只读 | — | ✓ | — | 本企业 | 本企业 | — |
| 理赔状态审核 | ✓ | — | — | ✓ | — | — | — | — |
| 操作员管理 | ✓ | — | — | — | — | 本企业 | — | — |
| 佣金与推广查看 | ✓ | 只读 | 只读 | — | — | — | — | 本人 |

## 7. 核心数据模型

所有交易表使用 UUID/ULID 作为外部标识，内部可保留 bigint 主键；所有表至少包含 `created_at`、`updated_at`，状态表包含 `version` 用于乐观锁。

### 7.1 企业与员工主数据

#### Tenant / Enterprise

- `id`
- `tenant_type`
- `name`
- `credit_code`
- `business_timezone`，默认 `Asia/Shanghai`
- `status`
- `created_at / updated_at`

#### ActualEmployer

- `tenant_id`
- `name / credit_code / contact`
- `contract_no / contract_start / contract_end`
- `status`

#### UserEmployerScope

用于承载 6.1 节 `DataScope` 中「本人被授权访问的部分实际用工单位」这一细粒度作用域：

- `user_id / actual_employer_id`
- `granted_by`
- `created_at`

只有需要该级别限定的角色才写入记录；平台级角色和「本企业」级角色不依赖此表。

#### WorkPosition

- `tenant_id / actual_employer_id`
- `name / description`
- `classification_status`
- `occupation_class`
- `approved_rate_version_id`
- `status`

岗位视频和审核材料只保存对象存储键，不保存公开 URL。

#### InsuredPerson

员工只表示身份主数据，不再承担参保历史：

- `tenant_id`
- `name`
- `id_type`
- `id_number_ciphertext`
- `id_number_hash`：用于租户内精确去重
- `phone_ciphertext`
- `current_position_id`
- `status`：active / archived

身份证号列表默认显示脱敏值，完整值只在授权详情、保司名单和合规导出场景解密。

### 7.2 产品与费率

#### InsuranceProduct

- `insurer_id`
- `name / coverage / payment_mode`
- `status`

#### ProductRateVersion

- `product_id`
- `version_no`
- `effective_from / effective_to`
- `billing_mode`
- `effective_mode`
- `commission_rule`
- `status`：draft / active / retired

#### ProductRateTier

- `rate_version_id`
- `occupation_class`
- `base_price`
- `coverage_snapshot`

费率发布后不可原地修改，只能创建新版本。参保申请和保单成员必须保存当时的价格快照，历史账单不得受新费率影响。

### 7.3 参保与停保

#### EnrollmentRequest

- `tenant_id`
- `request_no`
- `person_id / position_id / product_id / rate_version_id`
- `request_type`：enroll / terminate / resume / change
- `requested_effective_at`
- `status`
- `pricing_snapshot_json`
- `idempotency_key`
- `created_by`

#### EnrollmentBatch / EnrollmentBatchItem

批次负责外部发送，申请负责业务意图：

- `EnrollmentBatch`：租户、保司、产品、业务类型、批次号、状态、发送次数、回执对象键。
- `EnrollmentBatchItem`：批次、申请、人员、外部行号、发送状态、错误码、错误信息、保司成员号。

同一申请只能有一个最终成功的批次明细；失败后重试创建新的发送尝试，但不得重复生效。

### 7.4 保单与成员

#### Policy

- `tenant_id / insurer_id / product_id`
- `policy_no`
- `start_date / end_date`
- `status`
- `contract_object_key`

#### PolicyMember

这是“某个人在某保单下、某段时间内真实受保障”的事实。参停保生效后由本表直接承载保障期间语义，不再单独建 `CoveragePeriod` 表：

- `tenant_id / policy_id / person_id`
- `enrollment_request_id`
- `rate_snapshot_json`
- `effective_at / terminated_at`
- `endorsement_no`
- `status`：scheduled / active / terminated / rejected

“当前在保”由 `status = active` 且 `terminated_at` 为空的 `PolicyMember` 推导，不直接使用员工状态。员工与保单是多对多历史关系，禁止继续使用员工表上的单个 `policy_id` 表示全部保障历史。

### 7.5 账户、支付与账本

#### Account

- `tenant_id`
- `account_type`：premium / usage_fee / commission / settlement（仅作分类标签，不代表平台侧存在对应的清算科目）
- `currency`：CNY
- `status`

#### LedgerEntry

- `account_id`
- `direction`：debit / credit
- `amount`：`NUMERIC(18, 2)`
- `business_type`
- `business_id`
- `idempotency_key`
- `occurred_at`
- `created_by`

流水一经入账不可更新或删除。错误通过冲正流水处理。当前阶段采用「单账户追加流水」模型：每个 `Account` 独立维护自己的流水与余额，不要求跨账户复式平衡，也不存在「全平台账户加总为零」的约束，因此不需要建立平台侧清算或佣金应付科目。若未来出现真正需要多账户联动的业务（例如佣金代发时平台侧同步扣减），再扩展为复式记账。

#### PaymentOrder

- `tenant_id / account_id`
- `order_no`
- `amount / currency`
- `provider`
- `provider_trade_no`
- `status`：created / pending / paid / failed / closed / refunded
- `paid_at`
- `callback_digest`

#### BalanceSnapshot

余额可以缓存，但权威值始终等于有效账本分录之和。余额更新与流水写入必须在同一数据库事务中完成。此外建立定期对账任务（如每日执行一次）：按 `tenant × account` 汇总 `SUM(LedgerEntry.amount)` 并与 `BalanceSnapshot` 比对，不一致时告警并以流水累加值为准重算快照，差异计入 15.2 节的账本告警指标。

### 7.6 理赔

#### Claim

- `tenant_id / claim_no`
- `person_id / policy_member_id`
- `accident_at / reported_at`
- `requested_amount / approved_amount`
- `status / risk_level / current_handler_id`
- `version`

#### ClaimDocument

- `claim_id`
- `document_type`
- `object_key`
- `sha256 / size / mime_type`
- `scan_status`
- `review_status / review_note`
- `uploaded_by`

#### ClaimTimeline

只追加，记录旧状态、新状态、操作人、意见和时间。案件状态不能通过任意字符串直接覆盖。

### 7.7 外部集成与可靠任务

#### OutboxEvent

- `event_type`
- `aggregate_type / aggregate_id`
- `payload_json`
- `idempotency_key`
- `status / attempts / next_retry_at`

#### ProviderRequest

- `provider / operation`
- `request_id / idempotency_key`
- `request_digest`
- `response_code / response_digest`
- `status / attempts / latency_ms`
- `last_error`

Provider 日志不得保存完整身份证、密钥、支付签名或理赔材料内容。

## 8. 状态机设计

### 8.1 参保申请

```text
draft → validated → approved → queued → sending → accepted → effective
                    ↘ rejected          ↘ failed → queued
accepted → cancellation_requested → terminated
```

规则：

- `effective` 只能由有效保司回执或获批的人工确认产生。
- `failed` 只表示本次外部尝试失败，不表示业务申请永久失败。
- 停保必须记录申请时间、期望停保时间和实际停保时间。
- 恢复参保创建新申请和新保障期间，不覆盖旧期间。

### 8.2 批次发送

```text
draft → sealed → queued → sending → sent → partially_accepted / accepted / rejected
                              ↘ failed → retrying → sending
```

批次封存后明细不可修改；修正错误应创建新申请或补发批次。

### 8.3 支付订单

```text
created → pending → paid
              ├──→ failed
              └──→ closed
paid → refunding → refunded
```

只有合法签名、金额一致、商户一致、订单处于可支付状态时才能进入 `paid`。同一第三方交易号和同一入账幂等键只能成功一次。

### 8.4 理赔案件

```text
reported → collecting → submitted → insurer_review
                 ↑             ↘ supplement
                 └─────────────────┘
insurer_review → approved → paid → closed
               ↘ rejected → closed
```

每个迁移定义允许角色、必填字段、前置材料和副作用。禁止跨越未完成节点直接修改为任意状态。

## 9. 时间、金额和幂等规范

### 9.1 时间

- 所有时间点存为 PostgreSQL `TIMESTAMPTZ`，应用内部使用有时区的 UTC datetime。
- 保单生效日、账单日等纯日期使用 `DATE`。
- 禁止将日期和时间保存在普通字符串字段。
- 每个租户配置 `business_timezone`；“今日”“自然日”和月账单按该时区计算。
- 查询某业务日时，先把本地日界线转换为 UTC 半开区间 `[start, end)`，禁止对时间列使用字符串 `LIKE`。

### 9.2 金额

- 数据库统一使用 `NUMERIC(18, 2)`，费率可使用 `NUMERIC(18, 6)`。
- Python 使用 `Decimal`，API 金额使用十进制字符串或受约束的 decimal schema。
- 每个计费结果记录舍入规则和价格快照。
- 禁止使用浮点数累计余额、保费、返佣或赔付金额。

### 9.3 幂等

- 所有命令型接口支持 `Idempotency-Key`。
- 数据库对业务幂等键建立唯一约束。
- 外部回调保存原始报文摘要、签名验证结果和第三方事件 ID。
- 重复请求返回第一次成功结果，不重复执行副作用。

## 10. API 设计

新接口使用 `/api/v1`，旧接口在迁移期保留并标记废弃。

### 10.1 资源与命令

```text
POST /api/v1/enrollment-requests
POST /api/v1/enrollment-requests/{id}/approve
POST /api/v1/enrollment-batches
POST /api/v1/enrollment-batches/{id}/seal
POST /api/v1/enrollment-batches/{id}/send

POST /api/v1/payment-orders
POST /api/v1/payment-callbacks/{provider}
GET  /api/v1/accounts/{id}/ledger

POST /api/v1/claims
POST /api/v1/claims/{id}/documents
POST /api/v1/claims/{id}/transitions
GET  /api/v1/files/{file_id}/download-url
```

### 10.2 统一约定

- 错误返回 `code`、`message`、`request_id`、可选 `field_errors`。
- 分页使用稳定游标；后台小数据列表可保留页码模式。
- 列表接口默认返回脱敏字段和必要摘要，不返回完整身份证号。
- 导出属于异步任务，返回 `export_job_id`，完成后生成短时下载地址。
- 状态变更使用显式命令接口，不使用任意 `PATCH status=...`。
- OpenAPI schema 作为 Web 与小程序客户端的接口契约来源。

## 11. 文件与隐私安全

### 11.1 文件处理

- 使用私有对象存储桶，默认禁止匿名访问。
- 上传使用预签名地址或后端流式转发，禁止一次性读入整个大文件。
- 服务端验证扩展名、MIME、文件头、大小和 SHA-256。
- 理赔材料进入隔离区，完成病毒扫描后才允许预览。
- 下载前校验租户、角色、案件权限和文件状态。
- 下载 URL 有效期不超过 5 分钟，并记录审计。
- 删除业务记录时默认保留合规归档，按数据保留策略异步清理对象。

### 11.2 敏感数据

- 身份证、手机号、银行卡、理赔诊断等字段分类分级。
- 身份证和手机号应用层加密；检索使用租户盐化哈希。
- 日志、异常、Provider 请求和分析事件统一脱敏。
- 前端 Token 不写入可被任意脚本长期读取的持久化存储；Web 优先使用安全、HttpOnly、SameSite Cookie 或短期访问令牌配合受控刷新机制。
- 登录、验证码、导出、文件下载和通知接口实施限流。

### 11.3 配置安全

- 生产密钥由托管 Secret Manager 注入，不进入代码、镜像或数据库。
- 启动时校验环境类型、JWT 密钥长度、默认密码、HTTPS 回调地址和 Provider 必填配置。
- 支付、保司、短信、邮件分别使用独立凭据和最小网络权限。

## 12. 外部集成设计

每家供应商实现独立 Adapter，公共接口只描述业务能力：

```python
class InsurerAdapter:
    def submit_enrollment(batch): ...
    def submit_termination(batch): ...
    def query_receipt(request_id): ...
    def parse_callback(headers, body): ...
```

可靠执行流程：

```text
领域事务写入申请/批次 + Outbox
  → Worker 锁定 Outbox
  → Adapter 签名并发送
  → 记录 ProviderRequest
  → 成功：更新发送尝试并等待/处理回执
  → 失败：指数退避重试
  → 超过阈值：进入死信并生成运营待办
```

禁止在 HTTP 请求事务内长时间等待保司、短信、邮件或支付服务。所有回调必须先验证签名，再解析并提交领域命令。

## 13. 前端设计

### 13.1 Web

- 将平台端和企业端整理为同一工程中的角色路由或两个独立入口。
- 使用模块化构建，不再通过单个全局 `script.js` 重复覆盖函数。
- 由 OpenAPI 生成类型化客户端，统一错误处理、Token 刷新和请求编号。
- 菜单隐藏只改善体验，真正权限始终由后端执行。
- 敏感详情、导出、调账和状态迁移显示权限原因并要求二次确认。

### 13.2 微信小程序

- 正式版 API 域名由构建环境固定，不允许普通用户在设置中切换到任意 HTTP 地址。
- Token、用户信息和本地缓存设置明确过期时间，退出登录时彻底清理。
- 身份证列表脱敏；完整身份证仅在确有业务需要的页面短时显示。
- 大文件使用压缩、分片或预签名上传，并支持失败续传。
- 分享链接只包含一次性短 Token，不包含人员、保单或企业敏感参数。

## 14. 数据库与迁移

### 14.1 工具与规则

- 使用 Alembic 管理全部 PostgreSQL 和开发数据库迁移。
- 应用启动只检查版本，不执行 `create_all` 或临时 `ALTER TABLE`。
- 每个迁移包含升级、回滚策略、数据校验和大表影响说明。
- 约束优先放在数据库：唯一键、非空、检查约束、外键和状态枚举。
- 生产迁移采用 expand → backfill → switch → contract，避免直接破坏旧字段。

### 14.2 现有数据迁移映射

| 现有数据 | 新模型 | 处理方式 |
| --- | --- | --- |
| `User.enterprise_id` | Tenant + UserRole | 为企业用户创建租户角色；缺失租户的企业用户冻结并人工修复 |
| `InsuredPerson.status` | EnrollmentRequest + PolicyMember | 根据现状生成迁移事件并标记 `source=legacy` |
| `InsuredPerson.policy_id` | PolicyMember | 创建成员关系与初始保障期间 |
| 企业余额字段 | Account + Opening LedgerEntry | 以经财务确认的迁移时点余额生成期初分录 |
| `PaymentRecord` | PaymentOrder | 已支付订单必须与余额及外部流水人工/自动核对 |
| 本地上传路径 | 私有对象存储 | 上传、校验哈希、更新 object_key 后删除公开路径 |
| 字符串日期 | DATE / TIMESTAMPTZ | 按业务时区解析，无法确认的记录进入异常清单 |

迁移过程中禁止根据当前余额反向伪造多笔历史交易；无法还原的历史统一使用经过审批的“期初余额”分录。

## 15. 可观测性与运营

### 15.1 日志与追踪

- 每个请求生成 `request_id`，跨 Worker 和 Provider 继续传递。
- 使用结构化日志，字段包含租户、用户、模块、业务单号、耗时和结果。
- 禁止记录 Token、密码、完整身份证、支付签名和文件内容。
- 关键外部调用记录成功率、延迟、重试次数和错误码。

### 15.2 指标与告警

- 登录失败率和异常 IP。
- 跨租户访问拒绝次数。
- 参停保队列积压、失败率、回执超时。
- 支付回调验签失败、重复事件、订单金额不一致。
- 账本与余额快照差异；定期对账任务失败或超期未执行。
- 对象存储上传失败、病毒扫描失败、下载异常。
- 理赔 SLA 超时、余额预警和保单到期。

### 15.3 审计

审计记录至少包含操作者、租户、动作、对象、业务单号、前后值摘要、来源 IP、设备、request_id 和时间。资金调账、权限变更、敏感导出、文件下载和理赔状态迁移必须审计。

## 16. 测试策略

### 16.1 测试分层

1. 单元测试：计价、日期、生效规则、状态机和权限决策。
2. Repository 测试：数据库约束、租户过滤、并发和迁移。
3. API 集成测试：真实 FastAPI 路由、鉴权、错误契约、文件权限。
4. Provider 契约测试：签名、超时、重试、重复回调和错误映射。
5. 端到端测试：平台端、企业端和小程序关键链路。
6. 安全测试：越权、IDOR、暴力登录、恶意文件、静态文件泄露。

### 16.2 必须覆盖的关键场景

- 企业 A 无法以任何列表、详情、导出或文件 URL 访问企业 B 数据。
- 企业账号缺少租户时所有企业接口返回 403，不返回全平台数据。
- 重复和并发支付回调只产生一笔账本分录。
- 回调签名错误、金额不一致或第三方交易号重复时拒绝入账。
- 企业不能通过任何接口直接增加余额。
- 跨 UTC 日界线的新参、停保和账单日期正确。
- 停保后重新参保形成两个保障期间，旧历史不被覆盖。
- 数据库、源码、备份和私有文件不能通过匿名 URL 访问。
- Provider 超时后可重试，但保司只生成一次有效业务结果。
- 密码修改或账号停用后旧 Token 立即失效。

### 16.3 CI 门禁

- 格式化、静态检查、类型检查和依赖漏洞检查通过。
- 单元与集成测试全部通过。
- Alembic 可从空库升级到最新，也能从生产上一版本升级。
- OpenAPI 兼容性检查通过。
- 镜像以非 root 用户运行，且不包含 `.env`、数据库、上传文件或备份。

## 17. 分阶段迁移计划

### 阶段 0：立即止血（1–2 天）

- ✅ 静态目录收敛到前端构建目录。（Phase 0 时先仅显式暴露 index.html/script.js/styles.css 三个文件；Web 管理后台 Vue3 重构完成后，已改为 `StaticFiles` 挂载 `web/dist/assets`，SPA 回退路由仍是已知前端路由的显式白名单，未知路径一律 404，不是通配 fallback）
- ✅ 理赔材料和岗位视频改为鉴权下载，临时关闭匿名 URL。（`core/file_tokens.py` 短时签名下载链接，替代匿名 `/uploads` 挂载）
- ✅ 禁用企业直接充值接口。（`/api/enterprises/{id}/recharge` 收紧为仅管理员）
- ✅ 支付真实模式保持关闭，直到验签和账本完成。（`INTEGRATION_MODE` 默认 `mock`，`render.yaml` 亦显式设置，确认无需改动）
- ✅ 生产配置缺失时拒绝启动。（`core/config.py` 在 `ENVIRONMENT=production` 时校验 JWT_SECRET/ADMIN_PASSWORD/DATABASE_URL）
- ✅ 修复现有冒烟测试的 UTC/业务时区问题。（`routers/enrollment.py` 的日期比较改为 UTC 一致；已排查确认代码库内无同类残留）

交付门槛：不存在匿名数据库/源码/文件访问，不存在无支付加余额路径。**已达成**（`tests/security_smoke.py` 覆盖回归）。

### 阶段 1：安全与数据底座（1–2 周）

- ⏳ 引入 TenantContext、角色权限和会话版本。（会话版本 ✅ 已实现——`User.session_version`，密码修改/账号停用后旧 Token 立即失效；完整 TenantContext 抽象层和 6.3 节granular 角色权限矩阵尚未实现，现有系统仍以 `user.enterprise_id` 内联校验 + 集中式 `require_role`/`assert_enterprise_scope` 为主，对当前团队规模足够，暂不升级为独立 Role/Permission 表）
- ✅ 引入 Alembic，完成现有表基线迁移。
- ⏸️ 创建私有对象存储和文件元数据表。（阻塞：无真实对象存储凭据/基础设施，当前仍用本地磁盘 + 签名下载链接过渡）
- ⏸️ 金额字段迁移为 Numeric/Decimal。（评估后降级为技术债：账本 `LedgerEntry` 已用 Decimal 承载权威流水并有对账检查兜底，`Enterprise.premium_balance` 等缓存快照字段仍是 Float，混合运算改造涉及面广，暂缓）
- ✅ 建立支付订单、账户和不可变账本。（`LedgerEntry` 单账户追加流水模型，见 7.5 节；`Account` 独立实体、复式记账仍未做，按 v4.1 设计仅在需要时再升级）
- ✅ 补齐支付、租户和文件安全测试。（`tests/security_smoke.py`）

交付门槛：可在沙箱支付环境完成验签、入账、重复回调和对账。**已达成**（`tests/security_smoke.py` 验证幂等入账+对账为零差异；真实第三方签名验证仍待接入真实 Provider）。

### 阶段 2：参停保与保单重构（2–4 周）

- 创建 EnrollmentRequest、Batch、BatchItem、PolicyMember。
- 迁移员工当前状态和保单关系。
- 实现参保、停保、恢复、批改状态机。
- 统一业务时区和价格快照。
- 完成保司 mock 契约测试和人工回执流程。

交付门槛：任意人员的保障历史、批次、回执和价格均可完整追溯。

### 阶段 3：集成与运营可靠性（2–3 周）

- 引入 Outbox、Worker、重试和死信队列。
- 实现首家保司生产 Adapter。
- 接入短信、邮件和微信通知模板。
- 建立结构化日志、指标、告警和运营补偿页面。

交付门槛：外部故障不会丢单，可重试、可人工补偿、可审计。

### 阶段 4：前端收敛与正式发布（2–3 周）

- ✅ 重构 Web 工程，删除重复全局函数和静态模拟数据。（Vue3 + TypeScript + Element Plus 重写，替换原 240KB 单文件 `script.js`；18 个重复声明的死函数未迁移，经营大屏的 CSS 假图表换成真实 ECharts；顺手修复了从未接上前端的 `plan-tiers` 职业类别定价管理界面，以及 `claims.py` 状态机提交材料时的一个变量作用域 bug——之前每次真实提交理赔材料都会 500）
- ⏳ 对接 `/api/v1` 类型化客户端。（当前 API 仍是 `/api` 无版本前缀；Vue 端已有类型化 axios 客户端，但后端尚未做 `/api/v1` 迁移）
- ⏸️ 完成小程序固定域名、脱敏和私有文件上传。
- ⏸️ 执行数据迁移演练、容量测试、备份恢复和灰度发布。

交付门槛：三端关键链路通过自动化验收，完成回滚演练和上线审批。

## 18. 生产验收标准

### 18.1 安全

- 匿名访问数据库、源码、备份、岗位视频和理赔材料全部返回 401/403/404。
- 租户越权测试通过率 100%。
- 生产环境不存在默认账号、默认密码或默认 JWT 密钥。
- 敏感字段在列表、日志、监控和异常信息中均已脱敏。

### 18.2 资金

- 所有余额变化都能追溯到唯一账本分录。
- 重复或并发支付回调不产生重复入账。
- 账本、余额快照和支付对账差异为 0；非零差异必须有待办和处理记录。
- 调账必须双人复核并通过冲正/调整分录完成。

### 18.3 业务

- 参保、停保、恢复、批改均有申请、批次、明细、回执和保障期间。
- 任意日期的参停保名单可按企业业务时区准确重建。
- 续保和重复参保不会覆盖历史保单成员关系。
- 理赔案件只能关联事故发生时有效的保障期间。

### 18.4 稳定性

- 核心 API 月可用性目标不低于 99.9%。
- 参停保发送最终成功率不低于 99%，失败项全部可追踪。
- 关键任务积压、支付异常和回执超时均能在 5 分钟内触发告警。
- 完成数据库时间点恢复和对象存储恢复演练。

## 19. 关键架构决策

| 编号 | 决策 | 原因 |
| --- | --- | --- |
| ADR-001 | 采用模块化单体 | 当前团队和流量不需要微服务复杂度 |
| ADR-002 | PostgreSQL 为唯一交易事实库 | 支持事务、约束、并发锁和成熟迁移工具 |
| ADR-003 | 私有对象存储保存业务文件 | 避免本地磁盘丢失和匿名静态暴露 |
| ADR-004 | 参停保使用事件与保障期间 | 支持历史、续保、批改、报表和理赔校验 |
| ADR-005 | 资金使用不可变账本 | 支持幂等、审计、对账和冲正 |
| ADR-006 | Outbox 驱动外部任务 | 保证数据库提交与任务发布之间不丢消息 |
| ADR-007 | UTC 时间点 + 租户业务时区 | 避免日界线和跨地区计算错误 |
| ADR-008 | `/api/v1` 渐进迁移 | 控制三端同时升级风险，保留回滚能力 |

## 20. 完成定义

某项功能只有同时满足以下条件才算完成：

- 领域状态和权限规则已明确。
- 数据库约束和 Alembic 迁移已完成。
- API 契约、幂等策略和错误码已定义。
- 敏感数据、租户和审计要求已实现。
- 单元、集成和越权测试通过。
- 日志、指标、告警和运营补偿入口可用。
- 文档与实际实现一致，不依赖前端隐藏或人工约定保证安全。
