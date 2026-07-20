# 平台服务费微信商户号收款

- task_id: `wechat-merchant-usage-fee`
- owner: `Claude Code`
- status: `merged`（已合并到 `main`，合并提交见下方"提交"一节）
- branch: `worktree-wechat-merchant-usage-fee`（已合并，工作树与分支已清理）、
  `worktree-wechat-pay-hardening`（上线前加固后续任务，已合并，工作树与分支已清理）
- base_commit: `013a3a0f785ffc8d99e875b4a76bb3886998c461`
- migration_owner: `yes（本任务新增迁移 b7c8d9e0f1a2，已完成，未再有其他迁移在途，迁移锁已释放）`
- depends_on: `recharge-accounts-phase-a（已合并）`
- last_updated: `2026-07-21`

## 目标

新增微信商户号在线收款方式，作为平台服务费（使用费）的默认收款方式，与既有银行转账人工审核流程并存；管理员可在系统设置里配置收款商户号与密钥；微信支付成功后自动入账并生成可查询的支付记录。设计文档：
`docs/superpowers/specs/2026-07-20-wechat-merchant-usage-fee-design.md`；实施计划：
`docs/superpowers/plans/2026-07-20-wechat-merchant-usage-fee.md`（9 个任务，全部由 subagent-driven-development
执行，逐任务过 spec + quality 双重审查，最后一轮全分支复审）。

## 范围

- 允许修改：`backend/models/finance.py`、`backend/models/user.py`、
  `backend/migrations_alembic/versions/b7c8d9e0f1a2_*`、`backend/core/migrations.py`、
  `backend/schemas/finance.py`、`backend/schemas/wechat.py`（新增）、`backend/schemas/__init__.py`、
  `backend/services/settings.py`、`backend/providers.py`、`backend/routers/wechat.py`（新增）、
  `backend/routers/payments.py`、`backend/routers/recharge_requests.py`、`backend/app.py`、
  `web/src/api/payments.ts`（新增）、`web/src/api/recharge.ts`、
  `web/src/components/recharge/WeChatPayPanel.vue`（新增）、
  `web/src/views/recharge/RechargeCenterView.vue`、`web/package.json`、
  `miniprogram/pages/billing/billing.js`、以及全部对应测试文件。
- 明确不修改：保费（premium）账户微信支付、退款流程、周期性汇总报表生成、小程序新登录体系
  （均为设计文档中明确的范围外事项）。

## 公共文件与模块

- 计划修改：`backend/app.py`（路由注册）、`backend/routers/payments.py`（已有端点扩展）、
  `backend/services/settings.py`（SETTINGS_REGISTRY 追加）、
  `web/src/views/recharge/RechargeCenterView.vue`（整页重构）。
- 已确认与其他活动任务重叠：无（开始前 `bash scripts/ai_coordination_check.sh` 显示 0 个活跃非
  base 分支；`docs/ai-handoffs/` 内其余任务均为 `merged`/`released` 状态）。
- 处理方式：不适用（无重叠）。

## 数据库与 API

- 迁移：`b7c8d9e0f1a2_wechat_pay_fields`（down_revision `e5f6a7b8c9d0`，当前唯一 head）。
  `payment_records` 新增 `channel`（默认 `native`）、`openid`、`provider_trade_no`、`paid_at`；
  `users` 新增 `wx_openid`（唯一约束）。全部新增可空列，迁移与 SQLite 兼容桥均已做幂等判断。
  **`python3 scripts/pg_migration_check.py` 尚未针对真实 PostgreSQL 执行**（需要 Neon 凭据，本次自动化任务
  无法访问）——按 `CLAUDE.md` 合并门槛，这是合并前必须由人工补跑的一步。
- API 契约：新增 `POST /api/wechat/bind-openid`、`POST /api/payments/wechat-notify`、
  `GET /api/payments/{order_no}`、`GET /api/payments`；扩展 `POST /api/payments`
  （`account="usage"` 现调用微信支付，`channel` 参数新增，`account="premium"` 行为不变）、
  `GET /api/recharge/payment-account`（`account_type="usage"` 时新增 `default_method` 字段）。
- 兼容性：`tests/recharge_smoke.py` 中既有的 `create_payment(..., account="usage", ...)` 调用
  验证过行为不回归（仍返回 `status: "pending"`）；`POST /api/payments/callback`（内部/测试触发通道）
  保持不变。

## 提交

主功能分支（`013a3a0`..`8303933`，共 14 个提交，按任务分组，已以 fast-forward 合并到 `main`）：

- `58dfdbd` feat: add WeChat payment fields to payment_records and users（Task 1）
- `c454d35` feat: add WeChat payment schema fields and settings registry group（Task 2）
- `8ce7c87` fix: revert unsafe _load() exception swallowing, fix test DB setup instead（Task 2 修复）
- `e3ff96d` feat: add WeChat Pay provider (mock + real v3 signing/verification)（Task 3）
- `4ec5b53` fix: catch RealWeChatPayProvider signing failures instead of raising（Task 3 修复）
- `5f6dc8c` feat: add /api/wechat/bind-openid endpoint（Task 4）
- `5053b70` feat: wire WeChat Pay into /api/payments (native+jsapi orders, signed notify, status, admin list)（Task 5）
- `fc0de38` test: cover admin unrestricted access to GET /api/payments/{order_no}（Task 5 修复）
- `ab7fa11` feat: expose admin-configurable default usage-fee collection method（Task 6）
- `0ca82e6` feat(web): add WeChat payment API client and QR panel component（Task 7）
- `e989448` fix: clear existing poll timer before starting a new WeChat payment poll（Task 7 修复）
- `b6ac4d7` feat(web): add WeChat payment flow and records tab to the recharge center（Task 8）
- `8010ae7` feat(miniprogram): replace dead admin-only recharge call with real WeChat JSAPI payment（Task 9）
- `8303933` fix: address final-review Minor findings (test warning noise, double toast, response field ordering)（全分支复审修复）
- `7fff11a` docs: add handoff for WeChat merchant usage-fee payment branch

上线前加固后续分支（`7fff11a`..`b5bd603`，2 个提交，已以 fast-forward 合并到 `main`，见下方"风险"一节
三条中的前三条已全部处理）：

- `823545c` fix(payments): close pre-go-live hardening gaps in WeChat payment flow
- `b5bd603` test: verify row-lock precedes idempotency check, not just presence

## 验证

- `[x]` Python 测试（`system_smoke` / `security_smoke` / `participation_lock_smoke` / `recharge_smoke` /
  `salesperson_portal_smoke` / `settings_smoke` / `id_number_test` / `wechat_pay_config_test` /
  `wechat_pay_provider_test` / `wechat_pay_smoke` 全部通过；`compileall` 与 `git diff --check` 干净）
- `[ ]` Java 测试（本任务未涉及 Java 镜像，未跑；Java 后端只同步实体/Mapper，本任务无对应改动）
- `[x]` Web 构建（`npm run build` = `vue-tsc -b && vite build`，通过）
- `[ ]` 小程序检查/预览（`node --check billing.js` 通过；**未在微信开发者工具内做真机/模拟器交互验证**
  ——环境不具备 WeChat DevTools，需要人工在开发者工具内走一遍 Task 9 brief 里描述的验证流程：
  登录 `enterprise`/`enterprise123` → 资金与发票 → 立即充值 → 微信授权绑定 → JSAPI 下单 →
  `wx.requestPayment` 模拟支付/取消两种路径）
- `[ ]` 数据库空库与升级（SQLite 空库路径已通过 `startup()` 覆盖测试；**真实 PostgreSQL 升级路径
  未执行**，见下方风险）
- `[x]` 权限反向测试（企业只能查/建本单位订单，跨企业 403；`GET /api/payments` 与系统设置写入
  仅 admin；同一 openid 不可绑定两个账号，409）

## 风险、阻塞与下一动作

- 风险（全分支复审提出的三条"上线前必须处理"事项，均已在加固分支 `823545c`/`b5bd603` 中处理并合并；
  当前 `main` 上均已生效）：
  1. ~~`POST /api/payments/callback` 无鉴权且不校验签名，仍可直接给使用费账户入账。~~
     **已修复**：改为仅 admin 可调（`require_role("admin")`），保留作为管理员手工触发/排查工具，
     外部无法再匿名调用。`tests/security_smoke.py` 新增匿名 401、非 admin 403 的反向断言。
  2. ~~mock 模式下 `verify_notify` 用仓库里硬编码的固定密钥做 HMAC 校验，真实商户号配置好却忘记切
     `INTEGRATION_MODE=real` 时会变成可伪造回调。~~ **已修复**：`wechat_notify` 现在会在验签之前先
     检查——若 `INTEGRATION_MODE=mock` 但系统设置里已配置 `WECHAT_PAY_MCH_ID`，直接拒绝（503），
     不会尝试验签或处理请求体。
  3. ~~`payment_callback`/`wechat_notify` 的幂等判断是"先查后写"，没有行锁兜底，理论上并发重复
     通知可能双倍入账。~~ **已修复**：两处入账回调查询订单行时都加了 `SELECT ... FOR UPDATE`
     （`backend/routers/payments.py`），SQLite（本地/测试）静默忽略，PostgreSQL（生产）真正加行锁。
     `tests/wechat_pay_smoke.py` 新增按行号验证"加锁语句在幂等判断之前"的回归检查（而非仅检查
     字符串出现次数）。
- 阻塞：
  - **`python3 scripts/pg_migration_check.py` 仍未针对真实 PostgreSQL 执行**（需要 Neon 凭据，
    自动化任务无法访问）——按 `CLAUDE.md` 这是硬性合并门槛，SQLite 通过不能作为证据，需人工补跑。
  - **小程序端未在微信开发者工具内做真机/模拟器交互验证**（环境不具备 WeChat DevTools）——需人工
    登录 `enterprise`/`enterprise123` 走一遍：资金与发票 → 立即充值 → 微信授权绑定 → JSAPI 下单 →
    `wx.requestPayment` 模拟支付/取消两种路径。
  - 未经用户对本次发布明确授权，不得部署生产环境、不得改生产密钥、不得启用 `INTEGRATION_MODE=real`。
- 下一动作：
  1. 跑通 `pg_migration_check.py`。
  2. 在微信开发者工具内完成小程序端人工验证。
  3. 拿到真实微信支付商户号与密钥后，在系统设置的"微信支付"分组录入，确认以上两项都完成后再
     切换 `INTEGRATION_MODE=real`。三条安全加固事项已在合并前处理完毕，不再是上线阻塞项。
