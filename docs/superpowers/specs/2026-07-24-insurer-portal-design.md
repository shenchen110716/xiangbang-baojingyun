# 保险公司独立工作台 — 设计文档

- 日期：2026-07-24
- 状态：已确认设计，待写实现计划

## 背景与动机

参考现有"业务员工作台"（`AgentPortalView` + `role='salesperson'`）的模式，希望让保险公司也能登录系统，管理和自己相关的业务：编辑保司基本信息、岗位核保、上传保单、财务管理、发票管理、员工参停保异常标注、理赔管理。

**理赔管理这块有个现成的巧合**：`backend/routers/claims.py` 的理赔状态机里已经有一个 `insurer_review`（保司审核中）节点，`default_handlers` 字典里甚至已经写着 `'insurer_review':'保险公司理赔岗'`——但目前这个节点的状态流转权限只开放给 `admin`，从未真正连到过保司角色。这次工作台正好把这个早就设计好、却一直没接上真正账号的节点接起来。

**架构决定的翻转**：7-15 的《保司分账户充值与审核》设计明确决定"不引入独立的 Insurer 实体表"，`InsurancePlan.insurer` 和 `InsurerAccountLink.insurer` 都是自由文本字符串关联，理由是保持改动范围小、避免迁移风险。这次的保司工作台需求本质上要求保司账号能关联到"具体是哪一家保司"——字符串关联做不到可靠的数据隔离（同一家保司如果录入时写成"人保"和"人保保险"两种，会被当成两个不同保司，登录后要么漏看自己的数据，要么什么都看不到）。**这次翻转 7-15 的决定，引入独立的 `Insurer` 实体表**，`InsurancePlan`、`InsurerAccountLink` 都改为关联 `insurer_id`。

## 目标

1. 把"保险公司"从自由文本字符串升级为有 id 的实体，历史数据自动按字符串归类、平台事后提供合并工具处理录入不一致的情况。
2. 新增 `role='insurer'` 账号体系，登录门户支持保司端登录，参考 `salesperson` 的鉴权模式。
3. 保司登录后只能看到、只能操作自己产品线（`InsurancePlan.insurer_id == 自己`）下的数据，看不到其他保司的数据。
4. 落地六个功能模块：编辑保司基本信息（需平台审核生效）、岗位核保、上传保单、财务管理、发票管理、参保员工异常标注、理赔管理。

## 范围边界

- 仅 Web 端（管理端新增保司工作台入口）。小程序面向企业/员工端，不涉及保司角色，本次不改小程序。
- 保司只能"标注"参保记录异常（新增字段，写明原因），**不能直接修改参保状态本身**（参保/停保的状态机仍然只由企业端/平台端操作，保司没有权限直接停保或修改生效时间）——这是从"默认用户端数据，可以修改参停保员工异常状态及原因"这句需求里做的收窄：保司不是参停保的责任方，只是发现问题后标注、推动企业/平台处理。
- "财务管理"给保司看的是**保费结算维度的数据**（哪些企业欠费、已结算多少、账户余额），不暴露平台侧的内部字段（`insurance_base_price`/`profit_amount`/`total_commission_amount` 等，复用现有 `strip_internal_pricing` 的角色黑名单机制，给 `insurer` 角色也配一份自己的过滤规则，规则待实现时按"利润相关一律隐藏、结算价/保费可见"来定）。
- 保司基本信息编辑（名称/联系人/电话）提交后进入待审核状态，平台审核通过才生效——和岗位定类走的是类似的"提交-审核"两段式，不是直接生效。

## 数据模型

### `Insurer`（新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | int, PK | |
| name | string(100) | 当前生效的保司名称 |
| contact | string(80) | 联系人 |
| phone | string(30) | 联系电话 |
| status | string(20) | active / paused |
| pending_name | string(100), nullable | 保司提交的待审核名称，为空表示没有待审核变更 |
| pending_contact | string(80), nullable | |
| pending_phone | string(30), nullable | |
| pending_submitted_at | datetime, nullable | |
| created_at | datetime | |

保司提交编辑时只写 `pending_*` 三个字段，不动 `name`/`contact`/`phone`。平台审核通过后把 `pending_*` 的值搬进正式字段并清空 `pending_*`；驳回则只清空 `pending_*`，附驳回原因（复用现有 `audit` 记录里带 detail 的模式，不单独加驳回原因字段）。

### 迁移与历史数据归类

新迁移执行时：
1. 扫描 `InsurancePlan.insurer` 和 `InsurerAccountLink.insurer` 出现过的所有不同字符串值，每个不同字符串值自动创建一条 `Insurer` 记录（`name` = 该字符串）。
2. `InsurancePlan` 新增 `insurer_id`（可空，迁移时按字符串精确匹配回填；`insurer` 字符串字段保留不删，作为展示层过渡，后续任务里各读取点逐步切到 `insurer_id` 关联对象的 `name`）。
3. `InsurerAccountLink` 同样新增 `insurer_id` 并回填。
4. 平台端新增"保司管理"页面（参考"实际用工单位管理"的 CRUD 模式），额外提供一个"合并保司"工具：管理员选中两个（或多个）`Insurer` 记录，选一个作为保留目标，系统把其余记录名下的 `InsurancePlan.insurer_id`、`InsurerAccountLink.insurer_id` 批量改指到目标，再删除被合并的空记录。这是处理"人保"/"人保保险"这类历史重复录入的手工核对入口。

### `User` 扩展

- `role` 新增取值 `insurer`。
- 新增 `insurer_id`（可空外键，仅 `role='insurer'` 的账号使用，参考 `salesperson` 账号目前没有独立的"业务员实体表"、直接就是 User 记录本身的模式——但保司不同，`Insurer` 已经是独立实体，所以保司账号是"User 通过 insurer_id 关联到一个已存在的 Insurer"，不是"User 本身就是保司记录"）。

### `InsuredPerson` 异常标注（新增）

| 字段 | 类型 | 说明 |
|---|---|---|
| insurer_flag_reason | text, default "" | 保司标注的异常原因，空字符串表示当前没有标注 |
| insurer_flagged_at | datetime, nullable | |
| insurer_flagged_by | int, nullable, FK users.id | |

标注/取消标注是独立的一个接口（`PATCH /insured/{id}/insurer-flag`），只有 `role='insurer'` 且该员工所在岗位关联的 `plan.insurer_id` 是自己时才能操作；写入原因即标注，原因清空即取消标注。企业端和平台端的员工列表/详情页需要展示这个标注（红色提示条，参考现有 `notice-danger` 卡片样式）。

## 登录与权限

- `POST /auth/login` 的 `portal` 参数新增 `insurer` 分支，要求 `role == 'insurer'`；和 `salesperson` 一样是独立портал，不与 admin/enterprise 共用。
- `backend/core/rbac.py` 新增 `require_insurer_scope` 一类的依赖：从 `current_user` 拿 `insurer_id`，所有保司相关接口用它做查询过滤（对照 `agent_portal.py` 用 `user.id` 过滤业务员数据的模式）。
- Web 端新增 `web/src/views/insurer-portal/InsurerPortalView.vue`（独立整页组件，不套 `AppShell` 布局，参照 `AgentPortalView`）；`App.vue` 的 `isAuthPage` 列表加入 `insurer-portal`；`AppShell.vue` 加一条 `role === 'insurer'` 时自动跳转到 `insurer-portal` 的守卫；`web/src/router/routes.ts` 加对应路由；登录页 `portals` 数组加"04 · 保司端"选项。

## 五个功能模块

1. **保司基本信息编辑**：`GET/PATCH /insurer-portal/profile`，PATCH 写入 `pending_*` 字段；平台端"保司管理"页面新增"审核变更"入口（列出所有 `pending_submitted_at` 不为空的记录，通过/驳回）。
2. **岗位核保**：`PATCH /positions/{id}/review` 的 `require_role("admin", ...)` 依赖改成 `require_role("admin", "insurer", ...)`，函数体内 `role=='insurer'` 时额外校验该岗位关联方案的 `plan.insurer_id == user.insurer_id`，越权则 403。列表接口（`GET /positions`）同理按 `insurer_id` 过滤给保司角色看的结果集。
3. **上传保单**：`/policies/{id}/document/upload` 同样的权限收窄模式——保司只能给自己产品线下的保单传文件。
4. **财务管理**：新增 `GET /insurer-portal/settlement`，按自己 `insurer_id` 汇总已结算/待结算保费（复用 `services/pricing.py`/`services/ledger.py` 现有的结算查询逻辑，只是加一层按 `insurer_id` 的过滤和角色专属的字段黑名单）。
5. **发票管理**：`GET /insurer-portal/invoices`，复用 `backend/routers/invoices.py` 现有查询，按自己 `insurer_id` 关联的保单过滤。
6. **员工参停保异常标注**：见上面"数据模型"里的 `InsuredPerson` 扩展，`GET /insurer-portal/insured` 列出自己产品线下的参保记录（默认展示，只读），加 `PATCH .../insurer-flag` 标注接口。
7. **理赔管理**：`claims.py` 的 `claim_status` 权限收窄改成——`role=='insurer'` 时，只能在案件当前状态为 `insurer_review` 时操作，且案件必须挂在自己 `insurer_id` 下的保单/方案（通过 `claim.policy_id`→`policy.plan_id`→`plan.insurer_id`，`policy_id` 为空时退回 `person.policy_id` 同一条链路，和 7-23 批次里 `claim_payload()` 的归属判断逻辑一致）；可执行的下一状态限定为 `approved`（核赔通过，必须登记核赔金额）、`rejected`（拒赔，必须填写原因）、`supplement`（打回补件）。`claim_access()`（`services/claims.py`）也要加 `role=='insurer'` 的分支，允许查看（而非仅参与状态流转）自己产品线下、且已经流转到 `insurer_review` 或之后节点的案件（更早期节点如 `reported`/`collecting` 保司不需要介入，不开放查看，减少不必要的信息暴露）。

## 测试与验收

- 新迁移必须过 `scripts/pg_migration_check.py`（真实 PostgreSQL 验证）。
- 数据隔离是这次最核心的安全边界，需要专门的越权测试：保司 A 的账号访问保司 B 的岗位/保单/发票/结算/参保记录/理赔案件，全部应该 403 或者返回空列表（不能残留任何跨保司数据泄露的接口）。理赔这块额外测：保司不能操作还没到 `insurer_review` 节点的案件、不能跳过审核直接把案件标成 `paid`/`closed`。
- 保司信息编辑的"提交-审核"两段式需要覆盖：提交后 `name` 不变、`pending_name` 有值；审核通过后 `name` 更新、`pending_name` 清空；驳回后 `pending_name` 清空、`name` 不变。
