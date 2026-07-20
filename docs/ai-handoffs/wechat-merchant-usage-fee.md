# 平台服务费微信商户号收款

- task_id: `wechat-merchant-usage-fee`
- owner: `Claude Code`
- status: `ready`（本地全绿，待用户决定是否合并到 `main`）
- branch: `worktree-wechat-merchant-usage-fee`
- worktree: `/Users/madisonshen/Desktop/Demo/.claude/worktrees/wechat-merchant-usage-fee`
- base_commit: `013a3a0f785ffc8d99e875b4a76bb3886998c461`
- migration_owner: `yes（本任务新增迁移 b7c8d9e0f1a2，已完成，未再有其他迁移在途）`
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

（`013a3a0`..`8303933`，共 14 个提交，按任务分组）

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

- 风险（均为全分支复审确认为"不阻塞合并，但真实微信支付上线前必须处理"的事项，已按 mock 默认
  模式核实为当前不可利用）：
  1. **`POST /api/payments/callback` 无鉴权且不校验签名，仍可直接给使用费账户入账。** 这是本任务
     开始前就存在的端点（设计上是"内部/测试触发通道"），本任务未改变其行为，但把在线支付变成了
     使用费默认收款方式后，风险权重上升——真实微信支付上线前必须先给这个端点加鉴权（仅内部/admin）
     或彻底下线，否则任何人都能免费给任意企业充值使用费。
  2. **mock 模式下的 `verify_notify` 用仓库里硬编码的固定密钥（`MOCK_NOTIFY_SECRET`）做 HMAC 校验**，
     如果生产环境把微信商户密钥都配置好了却忘记把 `INTEGRATION_MODE` 切到 `real`，`wechat-notify`
     回调就会变成任何读过源码的人都能伪造。真实上线前应加一道启动期校验：只要配置了任意
     `WECHAT_PAY_*` 系统设置，就拒绝以 mock 模式运行（或反过来，mock 模式下 `wechat_notify` 直接拒绝）。
  3. **`payment_callback`/`wechat_notify` 的幂等判断是"先查后写"，没有行锁或数据库唯一约束兜底。**
     这是既有模式（`payment_callback` 本来就这样），但本任务新增的 `/payments/wechat-notify`
     是无鉴权、微信网关可能并发重试的端点，把这个理论竞态变成了预期场景。真实上线前应加
     `SELECT ... FOR UPDATE` 或对支付状态迁移加唯一约束，防止并发重复通知导致双倍入账。
- 阻塞：
  - 合并前需人工执行 `python3 scripts/pg_migration_check.py`（需 Neon 凭据）验证迁移在真实
    PostgreSQL 上可用——按 `CLAUDE.md` 这是硬性合并门槛，SQLite 通过不能作为证据。
  - 未经用户对本次发布明确授权，不得部署生产环境、不得改生产密钥、不得启用 `INTEGRATION_MODE=real`。
- 下一动作：
  1. 用户审阅本 handoff 与全分支复审结论，决定是否合并到 `main`。
  2. 合并前跑通 `pg_migration_check.py`。
  3. 在微信开发者工具内完成小程序端人工验证（见上方"验证"一节）。
  4. 拿到真实微信支付商户号与密钥后，在系统设置的"微信支付"分组录入，并按上方三条风险逐一处理
     后再切换 `INTEGRATION_MODE=real`。
