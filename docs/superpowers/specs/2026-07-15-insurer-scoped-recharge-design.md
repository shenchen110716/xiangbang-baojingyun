# 保司分账户充值与审核 — 设计文档

- 日期：2026-07-15
- 状态：已确认设计，待写实现计划
- 关联：本次是两个独立需求中的第一个（"充值/账户"），"业务员登录权限"是第二个独立 sub-project，单独立项。

## 背景与动机

参考同行"优米"（对公转账到指定账户，五日内打款，退款统一处理）和"子弹云"（对公转账，上传回单，自动识别到账）两个平台的充值方式截图。响帮帮保经云现状：

- `Enterprise` 有 `premium_balance`（保费账户）、`usage_balance`（使用费账户）两个字段，均为整个企业单一数值，不区分保司。
- 充值目前**只能由平台管理员手动操作**（`POST /enterprises/{id}/recharge`，`require_role("admin")`）。代码注释明确写着这是 Phase 0 stop-loss 项，因为企业自助充值曾经"零验证直接加余额"，先收紧为管理员专用工具，直到真正的支付/审核流程接上。
- 代码里已有一个 `PaymentRecord` + `/api/payments` + `/api/payments/callback` 的雏形，但从未接上任何前端——是网关回调式设计（更像微信支付/支付宝那种），跟参考图里的"对公转账+人工核对回单"模式不匹配，本次不复用它。
- `premium_balance`/`usage_balance` 目前**只被充值（credit）过，从未被消费逻辑扣减（debit）过**——它是一个"当前余额 vs 预测日均消耗"的预警投影数字，不是强制扣费的钱包，这让按保司拆分的改造成本可控。
- `InsurancePlan.insurer` 是自由文本字段（`String(100)`），系统里没有独立的 Insurer 实体（`/insurers` 页面其实是 InsurancePlan 的 CRUD 表单，"保险公司"只是导航文案）。

## 目标

1. 按保司（`insurer` 字符串）设置独立收款账户，保费充值时企业能看到该转去哪个账户。
2. 恢复企业自助发起充值的能力，但保留人工审核到账这道安全闸门（不倒退到"零验证"状态）。
3. 首页、经营大屏跟账户余额打通，突出显示余额和充值提醒（企业端），而不是像现在这样只在首页有一个孤立数字、大屏完全没有。

## 范围边界

- **仅 Web 端**（平台管理端 + 参保单位端）。小程序面向一线员工，不涉及财务操作，本次不改小程序。
- **不做 OCR 自动识别**回单金额——v1 是人工审核，OCR 作为后续可选优化，不在本次范围内。
- **使用费账户不拆分**——它是平台自己收的技术服务费，跟保司无关，继续用一个全平台固定收款账户。
- 不引入独立的 Insurer 实体表，收款账户按 `insurer` 字符串（与 `InsurancePlan.insurer` 对齐）关联，遵循现有 `insurer_email` 已经在用的"字符串关联"惯例。

## 数据模型

### `InsurerAccount`（新增，admin 管理）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| insurer | string(100) | 对应 `InsurancePlan.insurer` |
| bank_name | string(100) | 开户行 |
| account_no | string(60) | 银行账号 |
| account_holder | string(100) | 账户名称 |
| status | string(20) | active / paused |
| created_at | datetime | |

同一 `insurer` 只允许一条 `active` 记录（唯一性通过应用层校验，不加数据库唯一约束，参照本系统其它地方"暂停旧的、新增新的"的惯例）。

### `EnterprisePremiumAccount`（新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| enterprise_id | int, FK → enterprises | |
| insurer | string(100) | |
| balance | float, default 0 | |

`(enterprise_id, insurer)` 唯一。首次充值或首次消费预测时惰性创建（`get_or_create` 模式，参照 `_find_or_create_policy` 的既有写法）。

### `RechargeRequest`（新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| enterprise_id | int, FK | |
| account_type | string(20) | premium / usage |
| insurer | string(100), nullable | account_type=usage 时为 null |
| amount | float | |
| receipt_file_url | string(255) | 复用现有 uploads 短期签名下载模式（参照 `保单文件`/`claim document` 的做法） |
| status | string(20) | pending / confirmed / rejected |
| reject_reason | string(255), default "" | |
| created_by | int, FK → users | |
| confirmed_by | int, FK → users, nullable | |
| confirmed_at | datetime, nullable | |
| created_at | datetime | |

### `LedgerEntry`

新增可空字段 `insurer: string(100), nullable`。premium 类型条目记录对应保司；usage 类型条目留空。`account` 字段仍是 `premium`/`usage`，语义不变。

### `Enterprise.premium_balance` 的处理

保留字段本身（历史数据字段，不删列，避免破坏性迁移），但**停止读写**：

- 迁移脚本：为每个 `premium_balance != 0` 的企业创建一条 `EnterprisePremiumAccount(enterprise_id, insurer="未分类（历史余额）", balance=premium_balance)`，之后由管理员手动在后台把这笔历史余额拆分/改配到具体保司（改 `insurer` 字段值即可，不需要专门的拆分工具）。
- 迁移后，所有读取入口（dashboard、报表、余额预警）改为从 `EnterprisePremiumAccount` 按企业 `SUM(balance)` 或按保司分组读取，不再读 `Enterprise.premium_balance` 列。
- `usage_balance` 不受影响，继续用 `Enterprise.usage_balance`。

## API 设计

### 收款账户管理（admin only）

- `GET /api/insurer-accounts` — 列表
- `POST /api/insurer-accounts` — 新增
- `PATCH /api/insurer-accounts/{id}` — 编辑/暂停

### 充值申请

- `POST /api/recharge-requests` — 企业或管理员发起（企业只能填自己的 `enterprise_id`，与现有 `require_role("admin", "enterprise", ...)` 模式一致）。请求体：`account_type`, `insurer`(可选), `amount`, `receipt_file`（multipart）。校验：`account_type=premium` 时 `insurer` 必填且对应一条 `active` 的 `InsurerAccount`；金额 > 0。
- `GET /api/recharge-requests` — 列表，企业角色只看自己的，管理员看全部（复用现有 `user.role=="enterprise"` 过滤惯例）；支持 `status` 筛选。
- `PATCH /api/recharge-requests/{id}/confirm` — admin only。写 `LedgerEntry`（credit）+ 更新 `EnterprisePremiumAccount.balance` 或 `Enterprise.usage_balance` + `status=confirmed`，事务内完成（参照现有 `recharge_enterprise` 的写法）。
- `PATCH /api/recharge-requests/{id}/reject` — admin only，需要 `reason`，不动余额。

### 余额查询

- `GET /api/enterprises/{id}/premium-accounts` — 该企业按保司拆分的余额列表（企业本人 + admin 可查）。
- `GET /api/dashboard` 现有响应体调整：`premium_balance` 改为按保司拆分的数组 `premium_accounts: [{insurer, balance, days_left}]`，而不是单一汇总数字（admin 视角为跨企业按保司汇总）。`balance_alerts` 的计算逻辑同步增加 `insurer` 维度（当前按 `('premium', balance, daily)` 二元组遍历，改为对每个 `EnterprisePremiumAccount` 行分别计算 days_left）。

### 旧接口

`POST /enterprises/{id}/recharge`（admin-only 手动充值）保留不动，作为运营应急工具；新流程是常规路径，两者并存不冲突（旧接口本来就没有前端界面，只是后端保留的运维手段）。

## 前端设计

### 企业端（web）

- 新页面「账户充值」：选账户类型 → （premium 时）选保司 → 显示该保司收款账户信息（可复制）→ 填金额 → 上传回单 → 提交。提交后展示自己的充值记录列表（状态：待确认/已到账/已驳回，驳回显示原因）。
- `HomeView.vue`：原来的单一"保费账户余额" `StatTile` 改成按保司的小列表（每行：保司名、余额、预计可用天数），任一账户进入 warning/critical 时该行高亮并附「去充值」按钮，跳转到充值页并预选该保司。
- `ScreenView.vue`：新增一块余额健康度展示——企业角色显示自己按保司的余额条；admin 角色显示全平台低余额账户数量（复用 dashboard 已有的 `balance_alerts` 聚合，不重新计算）。

### 平台端（web，admin only）

- 「保险公司」页（`PlansAdminView.vue`）新增一个 tab 或独立小模块管理 `InsurerAccount`（增/改/暂停）。
- 新增「充值审核」页面（或挂在现有资金相关导航下）：待确认列表，点开看回单大图、确认/驳回操作。

## 错误处理

- 提交充值申请时保司没有配置 `active` 的收款账户 → 400，提示"该保司尚未配置收款账户，请联系平台"。
- 驳回时 `reason` 必填（参照现有岗位审核驳回的校验模式）。
- 回单文件类型限制 `.pdf/.jpg/.jpeg/.png`，大小上限参照现有保单文件上传（20MB）。

## 测试计划

- `tests/system_smoke.py` / `tests/security_smoke.py` 现有用例需要跑通（尤其是涉及 dashboard 响应体结构变化的部分，`premium_balance` 单一数字变成数组是破坏性的 API 契约变更，需要检查是否有测试断言了旧结构）。
- 手动验证：管理员配置两个保司的收款账户 → 企业分别对两个保司提交充值 → 管理员确认一笔、驳回一笔 → 确认企业端余额只增加了被确认的那笔，且按保司正确入账 → 首页和大屏都反映新余额。

## Java 后端

按本次会话一贯做法，Python 改完之后原样镜像到 `java-backend`（Controller/Mapper/Service 对应新增），保持字段命名通过 Jackson 的 snake_case 策略与 Python 接口一致。由于当前环境没有 JDK，Java 侧改动只能人工审查，不能编译验证——这个限制延续到本次实现。

## 与"业务员登录权限"需求的关系

两者除了都在同一个财务相关的大方向下之外，没有代码耦合——业务员权限改的是 `AgentCommission` 相关的可见性逻辑，不涉及本设计里的任何表。将作为独立 spec 单独头脑风暴、单独实现计划。
