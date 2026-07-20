# 平台服务费微信商户号收款 — 设计文档

- date: 2026-07-20
- status: draft（待用户审阅）
- 相关既有任务：`recharge-accounts-phase-a`（已合并，本设计的前置依赖已满足）

## 背景与目标

“平台服务费”（即使用费，`Enterprise.usage_balance`）目前只能通过线下银行转账 + 人工审核缴纳
（`backend/services/recharge.py` + `backend/routers/recharge_requests.py` 的 `RechargeRequest`
上传回单 → 管理员 `PATCH /recharge-requests/{id}/confirm` 流程）。

目标：新增“微信商户号”在线收款方式，并设为使用费的**默认**收款方式；管理员可在后台配置收款
商户号及密钥；用户微信支付成功后，系统自动入账并自动生成可查询的支付记录/报表。**原有银行转账
人工审核流程保留**，作为微信支付不可用时的备选方式。

范围仅限**平台服务费（使用费）**账户，不涉及保费（premium）账户——现有 `POST /api/payments`
已经对 `account="premium"` 做了拦截（"保费账户充值请使用「账户充值」页面提交充值申请，走审核
流程"），本设计不改变这一点。

## 关键决策（已与用户确认）

1. 新增在线支付通道，人工审核（银行转账）流程保留，两者并存，微信为默认。
2. 支付成功后的“报表和记录” = 复用现有 `LedgerEntry` 追加式账本自动入账 + 新增一个后台可查的
   微信支付订单列表页，不做独立的周期性汇总报表。
3. 微信支付商户密钥（API v3 密钥、私钥、证书）当前未拿到：先按项目现有
   `INTEGRATION_MODE=mock|real` 模式搭好完整框架，默认 mock，不发真实请求；管理员后续在系统
   设置里录入真实密钥后切到 real 模式即可上线，无需改代码。
4. 同时支持两种支付入口：Web 管理端扫码支付（Native）+ 小程序内支付（JSAPI）。小程序当前已经是
   参保单位（enterprise）门户（`portal: 'enterprise'` 登录已存在，见
   `miniprogram/app.js` / `pages/login`），**不需要新增登录入口**，只需在已有的
   `pages/billing` 页面里加一个微信支付入口。
5. JSAPI 所需的微信 openid，通过复用现有账号密码登录后，在小程序内额外调用 `wx.login()` 换取
   `code` 绑定到当前登录用户，不引入新的身份体系。

## 架构总览：复用现有在线支付链路，而非新建平行模型

代码库里已经存在一条通用在线支付链路，且已经支持 `account="usage"`：

- `PaymentRecord`（`backend/models/finance.py`）：`order_no / enterprise_id / account / amount /
  status / provider / created_at`。
- `POST /api/payments`（`backend/routers/payments.py`）：创建订单，调用
  `providers.payment_provider().create_payment(...)`。
- `POST /api/payments/callback`：按 `order_no` 幂等更新状态，`status=="paid"` 时给
  `enterprise.usage_balance`（或 premium）加余额，并调用
  `post_ledger_entry(session, ent, row.account, "credit", row.amount, "payment", row.order_no,
  idempotency_key=row.order_no)` 写入账本条目——这正是“自动生成记录”的既有机制。
- `GET /api/payments/reconcile`：现成的按状态计数对账入口。

**结论：本设计扩展这条链路，不新建平行的“微信支付订单”模型**，避免出现两套语义相近但字段不同
的支付记录表。

## 数据模型改动

### `payment_records` 表新增列（均为新增可空列，Alembic 迁移，无需回填）

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `channel` | String(20), default `"native"` | `native`（Web 扫码）或 `jsapi`（小程序内支付） |
| `openid` | String(64), nullable | JSAPI 支付时下单使用的 openid |
| `provider_trade_no` | String(80), nullable | 微信支付平台交易号（notify 回调中获取） |
| `paid_at` | DateTime, nullable | 支付成功时间 |

### `users` 表新增列

| 列名 | 类型 | 说明 |
| --- | --- | --- |
| `wx_openid` | String(64), nullable, unique | 绑定的微信 openid，小程序内 JSAPI 支付前必须已绑定 |

两处都是新增可空列的加法迁移，符合“布尔列默认值用 `sa.true()/sa.false()`”约束（本迁移不涉及
布尔列）。迁移需在真实 PostgreSQL 上执行验证（`scripts/pg_migration_check.py`），并基于最新已
合并 Alembic head 创建。

## Schema 改动

`backend/schemas/finance.py`：

```python
class PaymentIn(BaseModel):
    enterprise_id: int
    account: Literal["premium", "usage"] = "premium"
    amount: float = Field(gt=0)
    channel: Literal["native", "jsapi"] = "native"
```

新增：

```python
class WeChatBindOpenidIn(BaseModel):
    code: str  # wx.login() 返回的 code
```

## Provider 层（`backend/providers.py`）

新增 `wechat_pay_provider()`，与现有 `payment_provider()` 平行，同样受 `provider_mode()`
（`INTEGRATION_MODE`）控制：

```python
class WeChatPayProvider(MockProvider):
    def create_native_order(self, amount, order_no, description) -> ProviderResult: ...
    def create_jsapi_order(self, amount, order_no, openid, description) -> ProviderResult: ...
    def verify_notify(self, headers, raw_body) -> dict | None: ...  # 返回解密后的 payload，验签失败返回 None
    def code_to_openid(self, code) -> str | None: ...  # wx.login() code 换 openid

def wechat_pay_provider() -> WeChatPayProvider:
    return WeChatPayProvider("wechat") if provider_mode() == "mock" else RealWeChatPayProvider("wechat")
```

- **mock 模式**：`create_native_order`/`create_jsapi_order` 立即返回成功的假 `code_url`/
  jsapi 支付参数，订单状态仍为 `pending`（与今天的行为一致）；`code_to_openid` 返回一个确定性
  的假 openid（如 `f"mock-openid-{code}"`），便于冒烟测试。`verify_notify` 用一个固定的测试
  密钥做 HMAC 校验（而非真实微信证书验签）——签名正确则返回解密后的 payload，签名错误/缺失返回
  `None`，使 `POST /api/payments/wechat-notify` 端点本身（含验签失败分支）在 mock 模式下也能被
  冒烟测试完整覆盖；既有 `POST /api/payments/callback` 仍保留作为无需验签的内部/测试触发通道。
- **real 模式**（`RealWeChatPayProvider`）：实现微信支付 v3 API（RSA-SHA256 签名请求头、
  APIv3 密钥、商户私钥/证书），Native 下单调用
  `/v3/pay/transactions/native`，JSAPI 下单调用 `/v3/pay/transactions/jsapi`，
  `verify_notify` 校验微信平台证书签名并解密回调报文，`code_to_openid` 调用
  `/sns/jscode2session`（小程序 code2Session，与商户支付 API 分开的公众平台接口）。密钥来源
  见下节。

`payment_provider()` 保持不变（仍是通用 mock/http），新路径专用 `wechat_pay_provider()`。

## 路由改动（`backend/routers/payments.py`）

- **`POST /api/payments`**：`account=="usage"` 时改为调用 `wechat_pay_provider()`（按
  `data.channel` 选择 `create_native_order`/`create_jsapi_order`；`jsapi` 时要求
  `current_user.wx_openid` 已绑定，否则 400 提示先绑定）；`account=="premium"` 继续拦截，行为
  不变。
- **`POST /api/payments/callback`**：保留（用于 mock/内部触发，及非微信渠道的既有语义），把加
  余额 + 记账逻辑抽成小函数 `_apply_paid(session, row)`，供新 notify 端点复用。
- **新增 `POST /api/payments/wechat-notify`**：无鉴权（微信服务器直接调用），从请求头/body 用
  `wechat_pay_provider().verify_notify()` 验签解密，按解密后的 `out_trade_no` 查
  `PaymentRecord`，幂等（`status=="paid"` 直接返回），成功则写 `provider_trade_no`/`paid_at`
  并调用 `_apply_paid`。验签失败返回 400，不落库、不触发任何余额变更。
- **新增 `GET /api/payments/{order_no}`**：enterprise 只能查自己单位的订单（复用
  `assert_enterprise_scope`），admin 不限，用于 Web 扫码页轮询状态。
- **新增 `GET /api/payments`**（admin only）：按 `enterprise_id/status/provider/channel/日期区间`
  过滤的列表，支撑“微信支付记录”后台页面。
- **新增 `POST /api/wechat/bind-openid`**（`backend/routers/wechat.py` 新文件，或并入
  `payments.py`）：`require_role("admin","enterprise")`，取 `current_user`，调用
  `wechat_pay_provider().code_to_openid(data.code)`，写入 `current_user.wx_openid`（唯一性
  冲突时报错提示已被其他账号绑定）。

## 系统设置（`backend/services/settings.py` 的 `SETTINGS_REGISTRY`）

新增分组“微信支付”：

| key | secret | kind | 说明 |
| --- | --- | --- | --- |
| `WECHAT_PAY_MCH_ID` | 否 | text | 商户号 |
| `WECHAT_PAY_APP_ID` | 否 | text | 公众号/小程序 AppID |
| `WECHAT_PAY_API_V3_KEY` | 是 | password | APIv3 密钥，用于回调解密 |
| `WECHAT_PAY_CERT_SERIAL_NO` | 否 | text | 商户证书序列号 |
| `WECHAT_PAY_PRIVATE_KEY` | 是 | password | 商户 API 私钥（PEM） |
| `WECHAT_PAY_PLATFORM_CERT` | 是 | password | 微信支付平台证书（验签用） |

新增分组“使用费收款”（或并入现有分组）：

| key | secret | kind | 说明 |
| --- | --- | --- | --- |
| `USAGE_FEE_DEFAULT_METHOD` | 否 | select（`wechat`/`bank`） | 使用费默认收款方式，默认 `wechat` |

这些条目全部沿用既有 `admin_view()`/`set_many()` 逻辑（Fernet 加密、掩码回显、`audit()` 记录
变更），**无需新增管理端点或加密逻辑**，现有 `SystemSettingsView.vue` 会自动渲染新分组。

`GET /api/recharge/payment-account`（`recharge_payment_account_view`）在 `account_type="usage"`
时额外返回 `default_method`（读 `settings.get("USAGE_FEE_DEFAULT_METHOD", "wechat")`），供前端
决定默认选中的 Tab。

## 前端改动

### Web 管理端

- `web/src/views/recharge/RechargeCenterView.vue`：使用费缴纳新增“微信支付”Tab（按
  `default_method` 决定默认选中），提交后展示 `code_url` 生成的二维码（新增轻量 QR 生成，纯前
  端渲染，不请求外部图片服务），每 2 秒轮询 `GET /api/payments/{order_no}` 直至 `paid` 或超时，
  成功后刷新使用费余额；“银行转账”Tab 保留现有上传回单流程不变。
- 现有充值审核管理页新增“微信支付记录”Tab（列表页），调用新的 `GET /api/payments`，展示订单
  号/企业/金额/渠道/状态/支付时间，仅只读展示（微信支付无需人工确认这一步）。
- `web/src/api/types.ts` / `web/src/api/recharge.ts`：补充 `channel`/`default_method`/新增接口
  的类型与调用封装。

### 小程序（`miniprogram/pages/billing`）

- 新增“微信支付”按钮：若 `wx_openid` 未绑定，先 `wx.login()` 拿 `code` 调
  `POST /api/wechat/bind-openid`；随后 `POST /api/payments`（`channel:"jsapi"`）拿到支付参数，
  调用 `wx.requestPayment()`；支付完成后刷新使用费余额展示（复用现有余额展示逻辑）。

## 安全与合规

- 真实密钥（APIv3 密钥、商户私钥、平台证书）只经 Fernet 加密后入库，主密钥仅在环境变量，遵循
  项目既有安全姿态。
- `POST /api/payments/wechat-notify` 是唯一无鉴权端点，必须先验签再信任任何字段；验签失败一律
  400，不写库、不触发余额变更（避免伪造回调套现）。
- `wx_openid` 唯一约束，防止一个 openid 被绑定到多个企业账号从而绕过 `assert_enterprise_scope`
  产生越权代付。
- mock 模式下不会向微信发起任何真实请求，符合项目默认安全姿态。

## 测试计划

新增 `tests/wechat_pay_smoke.py`（沿用项目现有 smoke 测试风格，自带隔离 SQLite）：

1. mock 模式下 Native/JSAPI 下单成功，返回可用的 `code_url`/支付参数。
2. `wechat-notify` 未验签通过时拒绝，不改变订单状态、不加余额。
3. mock 验签通过后幂等入账：重复回调不会重复加余额、不会重复写 `LedgerEntry`。
4. 支付成功后 `enterprise.usage_balance` 增加、`LedgerEntry` 与 `PaymentRecord.paid_at`/
   `provider_trade_no` 均正确写入。
5. `channel="jsapi"` 但未绑定 `wx_openid` 时下单被拒绝（400，提示先绑定）。
6. RBAC：企业只能创建/查询本单位订单；`GET /api/payments`、系统设置写入仅 admin 可用。
7. 系统设置：微信支付分组密钥写入后正确加密、读取正确掩码（沿用 `settings_service` 现有测试
   模式）。
8. `account="premium"` 请求继续被拒绝（既有行为不回归）。

既有回归须全部通过：`system_smoke` / `security_smoke` / `participation_lock_smoke` /
`recharge_smoke` / `salesperson_portal_smoke`，以及 `web/npm run build`、
`python3 -m compileall backend`、`alembic` 单一 head 检查。

## 明确不做（本阶段范围外）

- 不涉及保费（premium）账户的微信支付。
- 不做独立的周期性（日/月）汇总报表生成任务，报表通过账本/订单列表查询派生。
- 不引入新的小程序登录体系，仅复用现有 enterprise 门户登录。
- 不做退款流程（如需，属于后续阶段）。

## 风险

- 真实微信支付密钥尚未拿到，real 模式的签名/验签逻辑在合并前只能用构造的样例数据做单元验证，
  无法端到端联调；上线前需要用真实商户号做一次沙箱/小额真实支付验证。
- 小程序 JSAPI 支付需要 AppID 与已认证的微信支付商户号完成授权绑定（微信商户平台操作），这一步
  不在代码改动范围内，需要用户在微信商户平台后台自行完成。
