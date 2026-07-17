# 业务员产品与佣金门户 v4.2 Phase 5

- task_id: `agent-commission-portal-phase5`
- owner: `Claude Code`
- status: `merged-deployed`
- branch: `feat/agent-commission-portal-phase5`（已合并至 `main`，合并提交 `285a026`）
- worktree: `/private/tmp/xiangbang-agent-portal`（可清理）
- base_commit: `ad35576`
- migration_owner: `no（迁移 `27951ec2f8ee` 已在生产验证通过，锁已释放）`
- depends_on: `salesperson-portal（已合并 b664e20）`
- last_updated: `2026-07-18`

## 目标

业务员可查看全部在售产品与平台最低销售价，以及**仅本人**的佣金指标、结算单与付款记录；
结算/付款/分配账本只追加，已确认金额不得原地改写。

执行计划：`docs/superpowers/plans/2026-07-17-agent-commission-portal-phase.md`（6 个任务）。

## 校准结果（Task 1 Step 1）

- 迁移锁空闲；head `7f0a1fa05267`，即本阶段 `down_revision`。
- 计划假设的既有接口全部属实：`plan_price_for_class`、`strip_internal_pricing`
  （`backend/services/pricing.py`）、`commission_accrual`、`agent_commission_rows`、
  `agent_commission_summary`（`backend/services/commissions.py`）。
- `InsurancePlan` 具备白名单所需的 `insurer`、`name`、`coverage`、`occupation_classes`、
  `billing_mode`、`effective_mode`、`status`。

## §5.1 已在生产被违反过，本阶段必须守住

`strip_internal_pricing` 曾只对 `enterprise` 角色脱敏，业务员可见保司结算底价与平台利润。
已由 `fix/agent-pricing-leak`（`0caa07b`）修复并发布：脱敏按角色查表，业务员保留
`minimum_sale_price` 但看不到成本构成。**那是减法式黑名单，属临时止血**；本阶段仍欠
`/api/agent-portal/products` 的**白名单 schema**——多一个字段就算泄漏，且新增产品列时
不会自动泄露。回归测试在 `tests/agent_pricing_visibility_test.py`，应扩展而非替换。

## Active Phase 5 Scope

- `AgentCommissionStatement` / `StatementItem` / `Payment` / `PaymentAllocation` 迁移与模型
- `backend/services/agent_settlement.py` 结算服务（追加式账本）
- `backend/routers/agent_portal.py` §14.4 契约
- Web 业务员门户页面

## 明确不做

- Java 镜像（Phase 6）、及时率相关（Phase 3/4 已完成）。

## 提交

- `c5f164c` test: 定义业务员门户白名单与隔离契约（红灯）
- `ca182e2` feat: 结算/结算项/付款/分配 schema、只追加账本服务、门户只读接口
- `8df923f` feat: 迁移 `27951ec2f8ee`（结算账本四表）
- `c7c865a` feat: `agent_settlement.py` 服务实现（余额四态、分次付款、多单核销、冲正）
- `76135bc` feat: `/api/agent-portal/*` §14.4 路由（产品/余额/明细/导出/结算单/付款）
- `dbeed14` feat: 扩展既有 `AgentPortalView.vue`（未新建第二套门户，遵守 §19）
- `8569bba` fix: 清理重复的 `agent_settlements.py`（复数），修正测试导入路径

## 验证（Task 6 Step 1，全部通过）

```
agent_settlement_model_test     PASS
agent_settlement_service_test   PASS
agent_portal_leakage_test       PASS
agent_portal_api_test           PASS
salesperson_portal_smoke        PASS
security_smoke                  PASS
system_smoke                    PASS
recharge_smoke                  PASS
participation_lock_smoke        PASS
employer_scope_smoke            PASS
frontend_routes_test            PASS
timeliness_smoke                PASS
agent_pricing_visibility_test   PASS
web/npm run build                ✓ built in 763ms
compileall / git diff --check    OK
alembic heads                    27951ec2f8ee（单头）
```

## Step 2 手动核对泄漏契约 — 已完成

以真实业务员账号读取 `/api/agent-portal/products` 原始响应，逐字段人眼核对：
响应恰好为白名单 10 个字段（`id/insurer/name/coverage/occupation_classes/
billing_mode/effective_mode/status/min_sale_price/my_commission_status`），
无 `price/cost/settle/profit/rebate/commission_rate/margin` 等成本字段，
`min_sale_price` 正确显示为底价+利润（100 原价、30% 费率、10 利润 → 80），
原价 100、底价 70、利润 10、费率 0.3、返佣 30 均未以任何字段出现。

## 合并与部署（2026-07-18）

- 合并提交：`285a026`（`Merge branch 'feat/agent-commission-portal-phase5'`），无冲突。
- 合并后在 `main` 上重跑完整验证矩阵（14 个测试 + web build + compileall + 单一迁移头
  `27951ec2f8ee`），全部通过；`git diff --check` 报告的空白问题出自与本次合并无关的
  既有未提交文件 `SYSTEM-DESIGN-V4.md`。
- 推送 `ad35576..285a026`，触发生产部署。**容器启动命令为
  `alembic upgrade head && uvicorn ...`（串行执行）**，故生产健康检查通过即可作为
  迁移在真实 PostgreSQL 上执行成功的证据（迁移失败会导致容器崩溃、uvicorn 不会启动）。
- 生产验证：`GET /api/health` → 200；`openapi.json` 中出现全部 8 条
  `/api/agent-portal/*` 路由；未鉴权请求 `/api/agent-portal/products` → 401
  （符合预期，非 500/崩溃）。
- 迁移 `27951ec2f8ee` 现已在真实 PostgreSQL 生产环境验证通过，风险已解除，迁移锁释放。

## 已知风险（迁移已验证后仍然存在）

- 结算单生成为人工触发，无周期自动结算调度。
- 分配余额上限（付款可分配余额、结算单未付余额）在服务层用行锁 + 事务强制，
  已在 SQLite 单连接下验证；未在真实两连接并发的 PostgreSQL 环境下专门压测
  （生产部署本身未暴露该并发场景的测试信号）。
- 凭证上传复用既有短时签名下载模式，不做静态挂载。
