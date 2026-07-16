# 业务员可见平台成本与企业经营数据热修复

- task_id: `agent-pricing-leak-hotfix`
- owner: `Claude Code`
- status: `merged`（已合并并发布生产）
- branch: `fix/agent-pricing-leak`
- worktree: `/private/tmp/xbb-agent-pricing-leak`
- base_commit: `6bb9fd7`
- migration_owner: `no（无迁移）`
- depends_on: `role-timeliness-v42 Phase 1（已合并发布）`
- last_updated: `2026-07-17`

## 背景

在准备 v4.2 Phase 5（业务员产品与佣金门户）计划时，核对设计基线 5.1 节的字段禁令，发现两处已在生产环境的越权数据暴露。均由实际运行探针确认，非静态推断。

## 缺陷一：业务员可见保司结算底价与平台利润

`GET /api/plans` 无角色门禁，任何登录用户可调；`strip_internal_pricing()` 写作
`if user.role != 'enterprise': return data`，只对企业角色脱敏，业务员原样返回。

探针实测业务员可见 25 个字段，其中 9 个为设计 5.1 明令禁止：

| 字段 | 实测值 | 性质 |
| --- | --- | --- |
| `insurance_base_price` / `price` | 100.0 | 保险原价 |
| `policy_floor_price` / `insurer_settlement_price` | 70.0 | 保司结算底价 |
| `profit_amount` | 10.0 | 平台利润 |
| `total_commission_rate` / `commission_rate` | 0.3 | 总返佣比例 |
| `total_commission_amount` | 30.0 | 总返佣金额 |
| `platform_margin_amount` | 10.0 | 平台毛利 |

成因：脱敏白名单的注释表明它设计时只考虑企业端（小程序与企业 HR），`salesperson`
角色由后续 `b664e20` 引入，白名单未同步。设计 5.1 明确要求「不能仅在前端隐藏内部字段」。

## 缺陷二：业务员可拉取全平台企业经营数据

`GET /api/dashboard` 与 `GET /api/screen/products` 均无角色门禁。二者仅对
`role == 'enterprise'` 施加企业过滤，业务员的 `enterprise_filter` 为 `None`，
因此 `dashboard()` 走 `session.query(Enterprise).all()`，返回平台全部企业与全部在保人员；
`screen_products()` 返回各产品的 `insured_count`、`enterprise_count`、`premium_total`。
设计 5.1 禁止向业务员返回「企业实际销售、投保和经营数据」。

## 修复

- `backend/services/pricing.py`：脱敏改为按角色查表。企业角色沿用原字段集；新增业务员字段集
  `_AGENT_INTERNAL_PRICING_FIELDS = _INTERNAL_PRICING_FIELDS - {'minimum_sale_price','minimum_sale_total'}`。
  业务员因此**仍可见平台最低销售价**（设计 5.1 允许且门户报价需要），但看不到其成本构成。
  管理员不受影响，保留全部可见性。`strip_internal_pricing` 是五个调用方
  （plans、dashboard、insured、reports、enterprises）的唯一收敛点，单点修复即覆盖全部价格泄露面。
- `backend/routers/dashboard.py`：`/dashboard` 与 `/screen/products` 加
  `require_role("admin", "enterprise")`，业务员 403 并引导至业务员门户。

## 影响面确认

- Web 业务员门户（`AgentPortalView.vue`）只调用 `/agents/me`，不使用 `/plans`、`/dashboard`、`/screen/products`。
- 小程序无业务员端（全仓无 `salesperson` 引用）。
- 故加门禁不影响任何现有界面；泄露此前仅可通过业务员 token 直接调用 API 触达。
- `/api/agents/me` 不经过 `strip_internal_pricing`，不受本次修改影响。

## 验证

- `[x]` `tests/agent_pricing_visibility_test.py`（新增，TDD 红→绿）：断言业务员看不到 9 个禁止字段、
  仍可见 `minimum_sale_price == 80.0`、可见全部在售产品、管理员保留全部可见性、
  两个大屏端点确实带角色门禁。
- `[x]` `employer_scope_smoke.py`、`employer_scope_model_test.py`、`employer_scope_service_test.py`
- `[x]` `security_smoke.py`、`system_smoke.py`、`recharge_smoke.py`
- `[x]` `participation_lock_smoke.py`、`salesperson_portal_smoke.py`
- `[x]` `python3 -m compileall -q backend`、`git diff --check`
- `[x]` 合并后在 `main` 上重跑：7 项烟测全通过；本次未改前端，Web 构建未受影响。

## 合并与发布

- 合并提交：`0caa07b`（`Merge branch 'fix/agent-pricing-leak'`）。
- 推送 `6bb9fd7..0caa07b`，经 Render `autoDeployTrigger: commit` 自动部署，生产 `/api/health` 200。
- 生产端到端未复验：`/api/dashboard` 无 token 时修复前后均为 401，外部无可区分信号，
  且本地无生产业务员账号，不应在生产建账号。修复由本地隔离库红→绿验证覆盖。

## 风险与后续

- 无数据库迁移，不占用迁移锁。
- 本次只堵住暴露面，未实现 Phase 5 的产品中心与「本人佣金配置状态」展示；
  `/api/plans` 对业务员仍返回全量目录（设计 5.1 允许），Phase 5 将在此基础上补齐门户 UI 与本人佣金口径。
- `pricing_snapshot()` 在 `relation=None` 时返回的 `agent_commission_*` 恒为 0，
  当前对业务员一并脱敏；Phase 5 若需展示「本人佣金配置状态」，应由独立的本人佣金接口提供，
  而非放宽本次白名单。
