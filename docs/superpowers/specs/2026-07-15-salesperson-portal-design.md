# 业务员登录权限（仅本人佣金可见）— 设计文档

- 日期：2026-07-15
- 状态：已确认设计，待写实现计划
- 关联：与"保司分账户充值与审核"（`2026-07-15-insurer-scoped-recharge-design.md`）是同一次头脑风暴里的第二个独立需求，两者除了都属于财务相关方向外没有代码耦合，单独立项。

## 背景

- "业务员"（代码里叫 `salesperson`）**已经是 `User` 表里的真实账号**：`POST /agents`（`backend/routers/agents.py`）创建时就带 `username`/`password_hash`，跟企业账号、平台账号是同一张表、同一套密码体系。
- 但 `POST /auth/login`（`backend/routers/auth.py`）目前只认 `portal in {"admin", "enterprise"}` 两种，`role="salesperson"` 的账号无论传哪个 portal 都会被拒（`user.role != "admin"`/`user.role != "enterprise"` 两条校验都过不去）。也就是说**业务员账号从创建那一刻起就没有任何办法登录**，不是权限太松，而是入口根本不存在。
- 佣金相关的数据聚合逻辑已经齐全，只是目前全部锁在 `require_role("admin")` 后面：
  - `commissions.commission_dict()` / `agent_commission_rows()` / `agent_commission_summary()`（`backend/services/commissions.py`）已经把"某业务员绑定了哪些投保单位、哪些保险产品、每个产品下多少在保人数、佣金明细与汇总"全部拼好了，`agent_commission_rows` 一次查询就同时覆盖了"业务员能看什么"（投保单位/产品基本信息）和"佣金明细"两块诉求。
  - 只是现有唯一的调用入口 `GET /agents/{item_id}/commissions` 要求 `role="admin"`，且需要显式传 `item_id`（管理员帮忙查任意业务员），没有"我查我自己"这种免传参的自助入口。
- 密码修改已经有通用接口 `POST /auth/change-password`（`backend/routers/auth.py`），基于 `current_user`，跟角色无关——业务员一旦能登录，这个接口天然就能用，不需要新写后端逻辑。

## 目标

1. 业务员能用已有账号密码登录，进入一个只属于他自己的门户。
2. 门户内容：佣金汇总 + 明细（含自己绑定的投保单位/保险产品基本信息），没有其他任何数据入口。
3. 业务员能自助修改密码（账号是管理员代建的，业务员自己不知道初始密码之外还需要能设置成自己记得住的密码）。

## 范围边界

- 不新增数据表、不改现有佣金计算逻辑——`AgentCommission`/`commission_accrual` 等现有服务函数原样复用。
- 不做业务员自助修改手机号/姓名等资料——按最小范围来。现状是 `routers/agents.py` 目前只有 `PATCH /agents/{id}/status`（启停状态），没有编辑姓名/手机号的端点，本次也不补，不在这次范围内。
- 不改动 admin 视角下现有的 `/agents`、`/agents/{id}/commissions`、`/agent-commissions` 这几个既有管理端点——它们继续只服务管理员查看全部业务员，本次只加一个业务员自助查自己的新入口，不复用/不修改旧入口的权限逻辑。
- 前端不复用 `AppShell`（侧边栏+多导航项），走独立极简页面，从设计上直接排除"业务员不小心点到别的导航项"的可能性。

## 后端设计

### 登录

`POST /auth/login` 新增分支：

```
if data.portal == "salesperson" and user.role != "salesperson": raise HTTPException(403, "该账号不是业务员账号")
```

`LoginIn.portal` 的类型从 `Literal["admin", "enterprise"]` 扩展为 `Literal["admin", "enterprise", "salesperson"]`（`backend/schemas.py`）。`_issue_token`/`current_user` 逻辑不用改——`role` 已经在 `User` 表里，token 签发和解析都是角色无关的。

### 自助佣金查询

新增 `GET /agents/me`，鉴权：`current_user` 的 `role` 必须是 `salesperson`（用 `require_role("salesperson", ...)` 或等价的 inline 检查，参照 `insured_status` 里"角色检查写在函数体内、不用 dependency"的两种既有写法皆可，选跟同文件其它端点一致的风格）。响应体：

```json
{
  "summary": { "enterprise_count": ..., "product_count": ..., "insured_count": ..., "total_commission": ... },
  "rows": [ { "enterprise_name": ..., "plan_name": ..., "insurer": ..., "insured_count": ..., "agent_commission_amount": ..., "agent_commission_total": ..., "status": ..., ... } ]
}
```

直接复用 `agent_commission_summary(session, user.id)` + `agent_commission_rows(session, user.id)`，`user.id` 取自 `current_user`（不接受外部传入 id，杜绝业务员查别人）。

## 前端设计

- `LoginView.vue` 的门户选择增加第三个选项"业务员"（`portal='salesperson'`）。
- 新增路由 `/agent-portal`，**不挂在 `AppShell` 下**（参照现有 `certificate`/`login` 路由绕过 `AppShell` 的 `isAuthPage` 判断方式，在 `App.vue` 里把 `route.name === 'agent-portal'` 也加进去）。
- 页面内容：顶部几个 `StatTile`（绑定单位数、产品数、在保人数、佣金总额，对应 `summary` 字段），下面一张表（单位、产品、保司、在保人数、佣金），右上角一个"修改密码"按钮弹出现有密码修改表单组件（如果 `PasswordChangeDialog` 之类的组件已存在就直接复用，不存在就新写一个最小的，调用 `POST /auth/change-password`）。
- 登录成功后跳转逻辑（`stores/auth.ts` 或路由守卫里）需要按 `role` 分流：`admin`/`enterprise` 继续跳 `/home`，`salesperson` 跳 `/agent-portal`。

## 错误处理

- 非 `salesperson` 角色的 token 访问 `/agent-portal`（比如管理员误打误撞打开这个 URL）→ 前端路由守卫检查 `auth.user?.role !== 'salesperson'` 时重定向到 `/home` 或 `/login`，避免空白页或后端 403 直接抛到用户面前。
- `GET /agents/me` 后端 403 时前端统一走现有的 401/403 拦截逻辑（`api/client.ts` 已有的清 token + 跳登录页模式）。

## 测试计划

- 手动验证：管理员新建一个业务员账号并绑定 1-2 个投保单位/产品的佣金关系 → 用该账号选"业务员"门户登录 → 确认能看到且只能看到自己绑定的那些数据 → 修改密码 → 用新密码重新登录成功。
- 回归验证：admin/enterprise 两个门户的登录、以及现有 `/agents`、`/agent-commissions` 管理端点行为不受影响。
- 用一个 `role='admin'` 或 `role='enterprise'` 的账号尝试以 `portal='salesperson'` 登录，确认被拒绝（403）。

## Java 后端

同步镜像：`AuthController` 的登录分支、`AgentController`（或对应位置）新增 `/agents/me`。本次会话延续的限制——当前环境没有 JDK，Java 侧只能人工审查。
