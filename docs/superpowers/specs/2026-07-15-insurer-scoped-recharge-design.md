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
4. 使用费余额耗尽时锁定参停保功能，充值到账后自动解锁，不需要人工操作。
5. 保费余额耗尽时，系统自动生成一条待处理的停保任务，由平台管理员确认后才真正执行停保（不允许系统自己直接停保真实的人）。
6. 充值确认/驳回、参停保被锁、停保任务产生/被执行，这几个重要节点直接短信通知到相关企业的所有账号（含主管和操作员）。
7. 参保人员年龄强制限制：未满 16 周岁不允许参保。

## 需求 4-7 的背景补充

- 系统里已经有 `sms_provider()`（`backend/providers.py`），mock/HTTP 两种模式，和 `insurer_provider`/`email_provider`/`payment_provider` 是同一套现成抽象；也已有 `/api/notifications/send` 通用发送接口，但目前没有任何业务事件会主动调用它——这次要把它接到真实触发点上。
- "操作员"就是 `role='enterprise', is_owner=False` 的企业子账号（`backend/routers/operators.py`），每个都有自己的 `phone` 字段。"含操作员"意味着通知要发给该企业下所有 `role='enterprise'` 的账号，不只是主管（`is_owner=True`）那一个。
- 系统里**没有定时任务**（这一点在修"临时日结"自动到期 bug 时已经确认过）。所以"使用费不足锁模块"和"保费不足触发停保"都不能依赖"到点自动执行"，只能是：前者做成实时余额判断（每次请求都查一下，天然精确），后者做成管理员访问相关页面时的惰性扫描（已在上面确认）。
- ID 号码校验（`backend/core/id_number.py` 的 `is_valid_id_number`）内部已经在解析生日（`birth = date(...)`），只是没往外暴露，年龄限制可以直接在这基础上加一个函数。

## 范围边界

- **仅 Web 端**（平台管理端 + 参保单位端）。小程序面向一线员工，不涉及财务操作，本次不改小程序。
- **不做 OCR 自动识别**回单金额——v1 是人工审核，OCR 作为后续可选优化，不在本次范围内。
- **使用费账户不拆分**——它是平台自己收的技术服务费，跟保司无关，继续用一个全平台固定收款账户。
- 不引入独立的 Insurer 实体表，收款账户按 `insurer` 字符串（与 `InsurancePlan.insurer` 对齐）关联，遵循现有 `insurer_email` 已经在用的"字符串关联"惯例。

## 数据模型

### 账户池化模型（重要：账户与保司是多对一，不是一对一）

现实中会出现"几个保司共用同一个收款账户"的情况——比如同一家经纪公司代收多家中小保司的保费，都走同一个结算账户。这种情况下，余额天然是按账户池化的，不是按保司分别计算的：企业转一笔钱进这个账户，覆盖的是该账户名下所有保司的欠费，而不是需要企业自己把一笔钱拆成好几份分别对应每个保司。

因此模型以 **账户（`InsurerAccount`）为主体**，`保司 → 账户` 是多对一映射（一个保司同一时间只能绑定一个账户，一个账户可以被多个保司绑定）：

### `InsurerAccount`（新增，admin 管理）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| label | string(100) | 账户备注名，如"平安/太平洋共用账户"，方便管理员识别 |
| bank_name | string(100) | 开户行 |
| account_no | string(60) | 银行账号 |
| account_holder | string(100) | 账户名称 |
| status | string(20) | active / paused |
| created_at | datetime | |

### `InsurerAccountLink`（新增，admin 管理）

保司名到收款账户的映射，多个保司可以指向同一个账户。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| insurer | string(100) | 对应 `InsurancePlan.insurer` |
| account_id | int, FK → InsurerAccount | |
| created_at | datetime | |

同一 `insurer` 只允许一条有效映射（应用层校验：新增映射前若该 insurer 已有映射，先要求管理员显式改掉旧的，避免一个保司同时挂在两个账户上导致余额判断歧义）。

### `EnterprisePremiumAccount`（新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| enterprise_id | int, FK → enterprises | |
| account_id | int, FK → InsurerAccount | |
| balance | float, default 0 | |

`(enterprise_id, account_id)` 唯一——余额挂在"企业 + 账户"上，不是"企业 + 保司"，所以共用账户的保司自然共享同一笔余额。首次充值或首次消费预测时惰性创建（`get_or_create` 模式，参照 `_find_or_create_policy` 的既有写法）。

### `RechargeRequest`（新增）

企业端选的是"保司"（这是他们实际认识的东西，账户是后台概念），提交时后端立刻按 `InsurerAccountLink` 解析出 `account_id` 存下来，充值确认时钱直接进那个账户的池子，跟企业选的是保司 A 还是保司 B（只要 A、B 共用账户）没有区别——这就是"金额不拆分，只判断一个账户余额是否够"的实现方式。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| enterprise_id | int, FK | |
| account_type | string(20) | premium / usage |
| insurer | string(100), nullable | 企业提交时选择的保司，仅用于展示/审计，account_type=usage 时为 null |
| account_id | int, FK → InsurerAccount, nullable | 提交时由 `insurer` 解析得出，account_type=usage 时为 null |
| amount | float | |
| receipt_file_url | string(255) | 复用现有 uploads 短期签名下载模式（参照 `保单文件`/`claim document` 的做法） |
| status | string(20) | pending / confirmed / rejected |
| reject_reason | string(255), default "" | |
| created_by | int, FK → users | |
| confirmed_by | int, FK → users, nullable | |
| confirmed_at | datetime, nullable | |
| created_at | datetime | |

### `LedgerEntry`

新增可空字段 `account_id: int, FK → InsurerAccount, nullable`。premium 类型条目记录对应账户；usage 类型条目留空。`account` 字段仍是 `premium`/`usage`，语义不变（这是"账户类型"，跟新的 `account_id` "具体哪个收款账户"是两个维度，命名上容易混，实现时用 `account_type`/`account_id` 两个不同字段名区分，避免混淆）。

### `PendingTermination`（新增）

保费余额耗尽时，惰性扫描生成的待处理停保任务，按账户池化——一个账户没钱了，挂在它上面的所有保司、所有在保人员都算受影响范围。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| enterprise_id | int, FK | |
| account_id | int, FK → InsurerAccount | |
| affected_insurers | string(255) | 该账户名下受影响的保司名，逗号分隔，供管理员快速了解范围 |
| affected_count | int | 检测到时受影响的在保人数（快照，供管理员评估影响） |
| status | string(20) | pending / confirmed / dismissed |
| confirmed_by | int, FK → users, nullable | |
| confirmed_at | datetime, nullable | |
| dismissed_at | datetime, nullable | 因企业及时充值而自动失效时写入 |
| created_at | datetime | |

`(enterprise_id, account_id, status='pending')` 同时只允许一条——扫描时如果已存在未处理的 pending 记录就不重复创建，避免管理员看到一堆重复任务。

### `Enterprise.premium_balance` 的处理

保留字段本身（历史数据字段，不删列，避免破坏性迁移），但**停止读写**：

- 迁移脚本：新建一个占位 `InsurerAccount(label="未分类（历史余额）", ...)`，为每个 `premium_balance != 0` 的企业创建一条 `EnterprisePremiumAccount(enterprise_id, account_id=占位账户, balance=premium_balance)`，之后由管理员手动在后台把这笔历史余额改配到具体账户（改 `account_id` 字段值即可，不需要专门的拆分工具）。
- 迁移后，所有读取入口（dashboard、报表、余额预警）改为从 `EnterprisePremiumAccount` 按企业 `SUM(balance)` 或按账户分组读取，不再读 `Enterprise.premium_balance` 列。
- `usage_balance` 不受影响，继续用 `Enterprise.usage_balance`。

## API 设计

### 收款账户管理（admin only）

- `GET /api/insurer-accounts` — 列表，每条附带该账户当前绑定的保司名列表（join `InsurerAccountLink`）
- `POST /api/insurer-accounts` — 新增账户
- `PATCH /api/insurer-accounts/{id}` — 编辑/暂停
- `GET /api/insurer-account-links` — 保司 → 账户映射列表
- `POST /api/insurer-account-links` — 新增映射（若该保司已有映射，先报错要求先处理旧映射，避免歧义）
- `DELETE /api/insurer-account-links/{id}` — 解绑（不影响已产生的历史余额/流水，只影响以后新提交的充值往哪个账户解析）

### 充值申请

- `POST /api/recharge-requests` — 企业或管理员发起（企业只能填自己的 `enterprise_id`，与现有 `require_role("admin", "enterprise", ...)` 模式一致）。请求体：`account_type`, `insurer`(可选), `amount`, `receipt_file`（multipart）。校验：`account_type=premium` 时 `insurer` 必填且在 `InsurerAccountLink` 中有对应的 `active` 账户，后端据此解析出 `account_id` 一并存下；金额 > 0。
- `GET /api/recharge-requests` — 列表，企业角色只看自己的，管理员看全部（复用现有 `user.role=="enterprise"` 过滤惯例）；支持 `status` 筛选。
- `PATCH /api/recharge-requests/{id}/confirm` — admin only。写 `LedgerEntry`（credit）+ 更新 `EnterprisePremiumAccount(enterprise_id, account_id).balance` 或 `Enterprise.usage_balance` + `status=confirmed`，事务内完成（参照现有 `recharge_enterprise` 的写法）。
- `PATCH /api/recharge-requests/{id}/reject` — admin only，需要 `reason`，不动余额。

### 余额查询

- `GET /api/enterprises/{id}/premium-accounts` — 该企业按账户拆分的余额列表，每条附带该账户名下的保司名列表（企业本人 + admin 可查）。
- `GET /api/dashboard` 现有响应体调整：`premium_balance` 改为按账户拆分的数组 `premium_accounts: [{account_id, label, insurers: [...], balance, days_left}]`，而不是单一汇总数字（admin 视角为跨企业按账户汇总）。`balance_alerts` 的计算逻辑同步增加 `account_id` 维度（当前按 `('premium', balance, daily)` 二元组遍历，改为对每个 `EnterprisePremiumAccount` 行分别计算 days_left）。

### 旧接口

`POST /enterprises/{id}/recharge`（admin-only 手动充值）保留不动，作为运营应急工具；新流程是常规路径，两者并存不冲突（旧接口本来就没有前端界面，只是后端保留的运维手段）。

### 使用费不足 → 锁定参停保

不新增表，纯实时判断。新增一个依赖函数 `require_usage_funded(enterprise)`：`enterprise.usage_balance <= 0` 时抛 `403`，提示"使用费余额不足，请先充值后再操作参停保"。挂载到 `routers/insured.py` 里所有会创建/变更参保状态的端点：`POST /insured`（新增）、`PATCH /insured/{id}`（含日期更正）、`PATCH /insured/{id}/status`（参保/停保）、`POST /insured/bulk`、`POST /insured/import-file`（批量导入）。`GET /insured`、`GET /insured/{id}/policy-members`（查看）不受影响，仍可正常浏览名单。企业一旦充值确认到账（`usage_balance` 变回正数），下一次请求这些端点时天然直接放行，不需要额外的"解锁"动作。

### 保费不足 → 待确认停保任务

- 惰性扫描函数 `scan_premium_shortfalls(session, enterprise_id=None)`：遍历 `EnterprisePremiumAccount` 中 `balance <= 0` 的行，对每一行：
  - 若已存在同 `(enterprise_id, account_id)` 的 `pending` `PendingTermination`，跳过。
  - 若不存在，找出该账户名下所有保司（`InsurerAccountLink`），统计该企业在这些保司名下当前 `active` 的 `InsuredPerson` 总数，创建一条 `pending` 记录（`affected_insurers` 记录保司名列表），触发预警短信（见下）。
  - 若存在 `pending` 记录但对应账户余额已经 > 0（企业充值了），把该记录标为 `dismissed`，不发短信（静默撤销，按确认过的方案）。
- 触发点：`GET /api/dashboard`（admin 视角）和 `GET /api/pending-terminations` 页面加载时调用一次（企业规模小，全量扫描成本可忽略；若后续企业数变大，可以只扫 `scoped` 范围）。
- `GET /api/pending-terminations` — admin only，列出所有 `pending` 任务。
- `POST /api/pending-terminations/{id}/confirm` — admin only。找出该账户名下所有保司、这些保司下该企业所有 `active` 的 `InsuredPerson`，对每个人调用既有的 `terminate_person_policy(session, person, terminated_at=business_now())`（立即停保，不走"最早可停保时间"那套面向自愿停保设计的规则——这是被动断保，不是主动选择停保时间），并把 `person.status` 置为 `stopped`；`PendingTermination.status=confirmed`。触发短信通知（见下）。
- 手动"驳回/忽略"暂不做——按确认过的方案，唯一的清除路径是充值后自动 dismiss；如果管理员判断不该停保，正确操作是先协调企业充值，而不是在系统里强行忽略一个真实的欠费状态。

### 短信通知

新增 `services/notify.py`：`notify_enterprise(session, enterprise_id, template, params)` —— 查出该企业所有 `role='enterprise'` 的账号（含操作员），对每个有 `phone` 的账号调用 `sms_provider().send_sms(...)`，fire-and-forget（不因为短信失败回滚主业务操作，失败只记 audit log，参照现有 provider 调用惯例都是"尽力而为"）。

触发点：
1. `PATCH /recharge-requests/{id}/confirm` / `/reject` 成功后。
2. `require_usage_funded` 抛出 403 的那一刻（第一次触发即发，同一天内不重复轰炸——用一个简单的"当天是否已发过"检查，基于 audit log 或专门的去重记录）。
3. `scan_premium_shortfalls` 新建 `PendingTermination` 记录时（预警）。
4. `POST /pending-terminations/{id}/confirm` 执行完停保后（告知实际受影响人数）。

### 参保年龄限制

`core/id_number.py` 新增 `age_from_id_number(value: str) -> int`，复用已有的生日解析逻辑，按当前日期（`business_today()`）计算周岁。在 `add_person`/`update_person`（身份证号变更时）/批量导入三处，`is_valid_id_number` 校验通过之后追加：年龄 < 16 → `400`，提示"参保人员须年满 16 周岁"。小程序端和批量导入共用同一个后端校验，不需要在前端各自实现一遍年龄计算（前端可以选择性地提前用生日做即时提示，但服务端强校验是唯一权威）。

## 前端设计

### 企业端（web）

- 新页面「账户充值」：选账户类型 → （premium 时）选保司 → 显示该保司对应的收款账户信息（可复制；如果该账户还绑定了其他保司，附一句提示"该账户同时用于 XX、XX 保司"，避免企业困惑为什么账户名和自己选的保司对不上）→ 填金额 → 上传回单 → 提交。提交后展示自己的充值记录列表（状态：待确认/已到账/已驳回，驳回显示原因）。
- `HomeView.vue`：原来的单一"保费账户余额" `StatTile` 改成按账户的小列表（每行：账户标签/保司名列表、余额、预计可用天数），任一账户进入 warning/critical 时该行高亮并附「去充值」按钮，跳转到充值页并预选该账户下任一保司。
- `ScreenView.vue`：新增一块余额健康度展示——企业角色显示自己按账户的余额条；admin 角色显示全平台低余额账户数量（复用 dashboard 已有的 `balance_alerts` 聚合，不重新计算）。
- 参停保相关操作（新增员工、参保、停保）如果后端返回使用费不足的 403，前端统一拦截显示一个明显的提示条/弹窗（"使用费余额不足，功能已锁定，请先充值"），带直达充值页的按钮，而不是让每个按钮各自弹一个普通错误 toast。

### 平台端（web，admin only）

- 「保险公司」页（`PlansAdminView.vue`）新增一个 tab 或独立小模块管理 `InsurerAccount`（增/改/暂停）+ `InsurerAccountLink`（哪些保司绑定这个账户）。
- 新增「充值审核」页面（或挂在现有资金相关导航下）：待确认列表，点开看回单大图、确认/驳回操作。
- 新增「待处理停保」页面：列出 `PendingTermination`，显示企业名、账户（附受影响的保司名列表）、受影响人数，点开可看具体人员名单，「确认停保」按钮需要二次确认弹窗（这是会真实停掉一批人保障的操作）。
- `HomeView.vue`（admin 视角）新增一个"待处理停保"数量提示，跟现有"待处理理赔"`StatTile` 并列。

## 错误处理

- 提交充值申请时保司没有配置 `active` 的收款账户 → 400，提示"该保司尚未配置收款账户，请联系平台"。
- 驳回时 `reason` 必填（参照现有岗位审核驳回的校验模式）。
- 回单文件类型限制 `.pdf/.jpg/.jpeg/.png`，大小上限参照现有保单文件上传（20MB）。
- 使用费不足时参停保端点统一返回 `403`，前端拦截成专门提示（见前端设计）。
- 参保年龄不足 16 周岁 → `400`，提示"参保人员须年满 16 周岁"。
- 短信发送失败不影响主流程（充值确认、锁定、停保确认本身都必须成功落库），短信是尽力而为的旁路通知。

## 测试计划

- `tests/system_smoke.py` / `tests/security_smoke.py` 现有用例需要跑通（尤其是涉及 dashboard 响应体结构变化的部分，`premium_balance` 单一数字变成数组是破坏性的 API 契约变更，需要检查是否有测试断言了旧结构）。
- 手动验证：管理员配置两个保司的收款账户 → 企业分别对两个保司提交充值 → 管理员确认一笔、驳回一笔 → 确认企业端余额只增加了被确认的那笔，且按保司正确入账 → 首页和大屏都反映新余额。
- 手动验证：把某企业 `usage_balance` 调到 0 → 确认参停保相关端点全部返回 403、短信已触发 → 充值确认后立即（不用等下一次任何"解锁"动作）验证可以正常参停保。
- 手动验证：把某企业某保司的 `EnterprisePremiumAccount.balance` 调到负数 → 管理员进入首页触发扫描 → 确认生成了一条 `PendingTermination`、短信已发 → 先测试"充值后自动 dismiss 不用管理员操作"这条路径 → 再测试"管理员确认后该保司下所有在保人员真的变成已停保"这条路径。
- 手动验证：用一个未满 16 周岁生日的身份证号尝试参保（新增/导入两条路径都测），确认被拒绝。

## Java 后端

按本次会话一贯做法，Python 改完之后原样镜像到 `java-backend`（Controller/Mapper/Service 对应新增），保持字段命名通过 Jackson 的 snake_case 策略与 Python 接口一致。由于当前环境没有 JDK，Java 侧改动只能人工审查，不能编译验证——这个限制延续到本次实现。

## 与"业务员登录权限"需求的关系

两者除了都在同一个财务相关的大方向下之外，没有代码耦合——业务员权限改的是 `AgentCommission` 相关的可见性逻辑，不涉及本设计里的任何表。将作为独立 spec 单独头脑风暴、单独实现计划。
