# 企业自助注册开户（响帮帮无忧保）— 设计文档

- 日期：2026-07-24
- 状态：已确认设计，待写实现计划

## 背景

用户希望推广"响帮帮无忧保"平台，目标是让企业主（投保单位）能自助签约开户，而不必等
后台员工在【企业管理】里手动新增。当前系统里：

- `Enterprise.status` 字段已存在 `pending`（待核验）/`approved`（已核验）两个值，
  且后台【企业管理】列表（`EnterprisesPanel.vue`）已经按这个字段筛选展示待核验企业——
  审核工作流本身已经存在，只是创建入口目前限定管理员（`POST /api/enterprises`
  要求 `require_role("admin")`）。
- 企业主登录账号通过 `POST /api/enterprises/{id}/admins` 单独创建，第一个账号自动
  `is_owner=True`；该接口同样要求管理员权限。
- `User.active` 字段门禁登录（`auth.py` 登录时检查，`active=False` 直接 403），但目前
  企业审核通过（状态改 `approved`）与账号激活（`active`）之间没有任何联动——两件事
  完全独立。
- 官网 `web/public/xbbzp.html`（单文件 hash 路由，见 2026-07-19 的营销页重构设计/计划）
  已有 `#/baojingyun` 二级页详细介绍平台，但没有任何"企业自助入驻"的行动点，全部
  引导到"登录入口"（面向已有账号的客户）。
- 营业执照 OCR 接口 `POST /api/ocr/business-license` 要求登录（`Depends(current_user)`），
  公开、未登录的申请流程无法复用它做自动识别。

## 目标

1. 企业主可以在无需登录的公开页面提交入驻申请（单位信息 + 登录账号），无需等待
   后台员工代录入。
2. 复用现有 `pending`/`approved` 审核字段和后台【企业管理】列表，不新增审核 UI、
   不新增数据表、不新增数据库迁移。
3. 审核通过后，企业主账号自动可登录；审核拒绝或未审核前，账号始终无法登录。
4. 官网营销页新增"企业免费入驻"入口，引导流量进入这条自助开户路径。

## 范围边界

- 不新建 `EnterpriseApplication` 之类的独立申请表——`Enterprise(status="pending")` 本身
  就是"未核验的申请"，转正只是改一个字段，不做额外的"申请→转换成正式企业"的搬迁逻辑。
- 不做营业执照上传/OCR——OCR 接口要求登录，公开流程绕不过去；而存储上传文件的引用
  需要给 `Enterprise` 加一个新字段（如 `license_url`），这会触发一次新迁移，与本设计
  "不新增数据库迁移"的边界冲突。本版申请表单只收文本字段；审核时如需核验营业执照，
  后台人工联系申请人索要即可，不在本设计范围内。
- 不加验证码、不加短信验证、不加限流——现阶段是演示/内部环境；仅做服务端基础校验
  （必填项、用户名唯一性、统一社会信用代码去重）。若后续该页面要接真实公网流量招揽
  真实客户，验证码/限流需要单独立项加固，本设计不覆盖。
- 不给后台【企业管理】列表加"来源"标记区分自助提交与员工代录——待核验筛选已经够用。
- 不涉及短信/邮件通知申请人或管理员——申请与审核结果都通过后台现有列表和企业主自行
  登录查看，不新增 `notify.py` 调用。
- 不涉及实际生产部署——本设计只覆盖功能实现与本地验证；build/测试/合并/push 触发
  Render 部署按此前发布清单在实现完成后单独向用户确认执行。

## 后端改动

### 新增：`POST /api/enterprises/apply`（公开，无鉴权依赖）

- 入参（新 Pydantic schema，独立于现有 `EnterpriseIn`，只暴露申请必需字段）：
  `enterprise_name`、`credit_code`、`contact`、`phone`、`username`、`password`。
- 校验（服务端，均返回 4xx + 明确中文提示）：
  - 必填项非空（单位名称/联系人/联系电话/账号/密码）。
  - `username` 未被现有 `User` 占用。
  - `credit_code` 非空时，若已存在状态不为 `rejected` 的 `Enterprise` 使用同一
    `credit_code`，拒绝提交，提示"该单位已提交过申请，请等待审核或联系客服"。
- 成功后在同一事务内创建：
  1. `Enterprise(name=enterprise_name, credit_code=..., contact=..., phone=...,
     status="pending")`（其余字段用模型默认值，如 `premium_balance=0`、
     `usage_fee_daily=0.1`，与现有员工代录路径一致）。
  2. `User(username=..., password_hash=pwd.hash(password), name=contact, phone=...,
     role="enterprise", enterprise_id=新企业id, is_owner=True,
     enterprise_role="owner", active=False)`——`active=False` 是关键，审核通过前
     不能登录。
- 响应：只返回极简确认体 `{"message": "提交成功，请等待审核"}`，**不**调用现有
  `serialize()` 返回完整企业对象——避免把内部字段结构暴露给未登录调用方（呼应
  `strip_internal_pricing` 在别处体现的"公开/低权限视图不暴露内部字段"原则）。
- 审计：调用现有 `audit()`，`action="apply"`，`entity="enterprise"`，与其他写操作
  记录方式一致。

### 修改：`PATCH /api/enterprises/{item_id}/status`（现有接口，鉴权不变）

- 现状：只是 `item.status = status_value` 后 commit，不涉及关联账号。
- 新增逻辑：当 `status_value == "approved"` 时，同时把该企业下 `is_owner=True` 的
  `User` 记录 `active` 置为 `True`（若存在）。
- `status_value` 为其他值（含 `rejected`）时不改账号状态——账号保持创建时的
  `active=False`，申请人始终无法登录，符合"未通过审核就不能用"的预期。

### 不改动的部分

- `POST /api/enterprises`、`POST /api/enterprises/{id}/admins`（员工代录路径）
  保持现状，管理员仍可像现在一样手动新增企业和账号。
- `EnterprisesPanel.vue` 的待核验列表/筛选完全不改——自助提交的 `pending` 企业
  会自然出现在这个既有列表里。

### 修正：后台目前没有"审核"按钮（写 spec 时的错误假设）

调查代码后发现 `EnterprisesPanel.vue` 从未调用过已存在的 `setEnterpriseStatus`
API 封装——待核验/已核验目前只是一个只读筛选条件，没有任何按钮能把状态从
`pending` 改成 `approved`。这意味着不管是自助申请还是员工代录，企业一旦创建就
永远停在 `pending`，没有人工能推进审核。这是本设计能跑通的必要前提，不算范围
扩大：列表操作列加一个"审核通过"按钮（`pending` 状态下可见），调用现有
`setEnterpriseStatus(id, 'approved')`。不加"驳回"按钮——驳回没有紧急性，
管理员可以暂时不处理（如需要，后续单独加）。

## 前端改动

### 新增：公开申请页面（Vue，无需登录）

- 新增路由（例如 `/enterprise-apply`），比照 `LoginView.vue` 的公开路由模式
  （不挂在需要鉴权的布局下），并按 CLAUDE.md 要求加进 `app.py` 的
  `_FRONTEND_ROUTES` 白名单。
- 表单字段与后端入参一一对应，复用 Element Plus 表单组件与现有
  `EnterprisesPanel.vue` 新增企业对话框类似的校验风格（必填项前端先挡一轮，
  服务端二次校验为准）。
- 提交成功后展示"提交成功，请等待审核"提示，并引导返回官网或前往登录页
  （审核通过后可用申请时的账号密码登录）。

### 修改：`web/public/xbbzp.html`

- 首页 hero 区域和 `#/baojingyun` 二级页各新增一个"企业免费入驻"CTA 按钮，
  链接到新的 `/enterprise-apply` 页面（与现有"登录入口"CTA 并列，视觉复用
  现有 `.nav-cta`/按钮样式，不引入新样式系统）。
- 其余内容、导航结构、视觉规范不变（沿用 2026-07-19 营销页重构设计确立的
  navy+amber 配色与 5-view 结构）。

## 测试

- 新增后端 smoke 测试（仿照现有 `tests/*_smoke.py`，独立临时 SQLite）：
  1. 提交申请 → 查库确认 `Enterprise.status == "pending"` 且关联 `User.active == False`。
  2. 用重复 `credit_code` 再次提交 → 返回拒绝，明确错误信息。
  3. 管理员调用现有审核接口将状态改为 `approved` → 关联账号 `active == True`。
  4. 用申请时提交的账号密码调用登录接口 → 登录成功。
  5. （反例）状态改为 `rejected` 时账号仍 `active == False`，登录失败。
- 前端验证：`cd web && npm run build` 无报错；本地打开新页面完整走一遍
  提交 → 后台【企业管理】待核验列表核对数据 → 审核通过 → 用申请账号登录成功
  的全链路；确认 `xbbzp.html` 新 CTA 跳转正确、响应式和深色模式不受影响。

## 发布

设计范围只到功能实现 + 本地验证。是否 build/合并/推送触发 Render 自动部署，
按此前发布清单（工作树干净度确认 → 测试 → build → 合并 main → push）在实现
完成后单独向用户确认，不在实现计划里默认包含推送生产这一步。

## 已知限制（明确不在本次范围内）

- 公开接口无验证码/限流，理论上可被脚本刷申请堆积待核验队列——现阶段可接受，
  真上公网获客前需要补防护。
- 拒绝的申请不会自动清理或提醒申请人，企业主需要自行观察登录是否可用或联系客服。
