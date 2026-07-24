# 保险公司独立工作台 — 交接记录

- 状态：已合并至 `main`（含 Java 镜像同步，已 Maven 编译验证）；PostgreSQL 迁移校验仍待补（见下）
- 分支：`worktree-insurer-portal`（已合并，工作树已回收）
- 涉及范围：新 Insurer 实体表 + 迁移、用户/认证/RBAC、岗位、保单、发票、参保员工、理赔（用户/认证/RBAC/公共路由/数据库迁移，按 CLAUDE.md 需串行修改的模块）

## 修改文件
- 迁移：`backend/migrations_alembic/versions/b4f19a7d2e63_add_insurer_entity.py`（新增 insurers 表，insurance_plans/insurer_account_links/users/insured_people 加列）
- 模型：`backend/models/insurer.py`（新增），`backend/models/user.py`、`plan.py`、`finance_accounts.py`、`insured.py`（扩展）
- 核心：`backend/core/rbac.py`、`backend/core/security.py`、`backend/services/insurer_scope.py`（新增）、`backend/services/pricing.py`、`backend/services/insurer_settlement.py`（新增）
- 路由：`backend/routers/insurers.py`（新增，平台端保司主体管理）、`backend/routers/insurer_portal.py`（新增，保司工作台）、`auth.py`、`positions.py`、`reports.py`、`invoices.py`、`claims.py`、`insured.py`（收窄权限/加过滤，均为既有路由扩展，非重写）
- Web：`web/src/views/insurers/InsurerManagementView.vue`（新增）、`web/src/views/insurer-portal/InsurerPortalView.vue`（新增，7 个 tab）、路由/登录页/AppShell/App.vue 的保司端接入
- 测试（本任务新增）：`tests/insurer_full_isolation_smoke.py`

## 测试
以下均在本地 SQLite（每个 smoke 自带隔离临时库）跑过，全部通过：

```
python3 -m compileall -q backend                      -> COMPILE OK
python3 tests/insurer_full_isolation_smoke.py          -> insurer_full_isolation_smoke: OK
python3 tests/system_smoke.py                          -> system smoke: ok
python3 tests/security_smoke.py                        -> security smoke: ok
python3 tests/participation_lock_smoke.py               -> participation lock smoke: ok
python3 tests/insurer_rbac_test.py                      -> insurer rbac test: PASS
python3 tests/insurer_admin_test.py                     -> All insurer admin tests: PASS
python3 tests/insurer_profile_test.py                   -> All insurer profile tests: PASS
python3 tests/insurer_positions_scope_test.py            -> All insurer positions scope tests: PASS
python3 tests/insurer_policy_upload_scope_test.py         -> All insurer policy upload scope tests: PASS
python3 tests/insurer_settlement_test.py                 -> All insurer settlement tests: PASS
python3 tests/insurer_invoices_scope_test.py              -> All insurer invoices scope tests: PASS
python3 tests/insurer_flag_test.py                        -> insurer_flag_test.py: all tests passed
python3 tests/insurer_claims_scope_test.py                -> All insurer claims scope tests: PASS
```

`insurer_full_isolation_smoke.py` is a new end-to-end smoke test (not a replacement for the per-module
scope tests above) that logs in as one insurer-A account and, in a single run, walks all seven modules
(岗位/保单/发票/参保员工/理赔/参停保标注/财务结算) confirming insurer A cannot read or write insurer B's
records anywhere: positions list, policies list, invoices list, `/api/insurer-portal/insured` list, claims
list, and `/api/insurer-portal/settlement` rows all exclude insurer B's records; the insurer-flag PATCH,
claim-status PATCH, position-review PATCH, and policy-document-upload POST against insurer B's records all
return 403 (position-review also accepts 400, matching the per-module test's tolerance for that endpoint's
own validation ordering).

- 迁移未能过 `scripts/pg_migration_check.py`（见下方“风险与后续”，非代码问题，是本执行环境缺少凭据）。
- 现有 `system_smoke.py` / `security_smoke.py` 基线未回归。

## 风险与后续（2026-07-24 更新）

- **PostgreSQL 迁移验证：仍未完成，非代码问题，依旧卡在凭据缺失。** 本执行环境依旧没有 `NEON_API_KEY`
  环境变量，也没有 `~/.neon_api_key` 文件，`python3 scripts/pg_migration_check.py` 无法运行。迁移文件
  （`b4f19a7d2e63_add_insurer_entity.py`、`c7a2f4e91b38_insurer_credit_code_email_address.py`）仍然只在
  SQLite 上验证过。**在获得 Neon 凭据并成功跑通 `pg_migration_check.py` 之前，不应认为这两个迁移已在生产
  环境验证。** 下一位有权限的代理/人工需要设置 `NEON_API_KEY` 后重跑此脚本。
- **Java 镜像后端同步：已完成并已用 Maven 编译验证通过。** `Insurer.java`/`InsurerMapper.java` 已新建，
  `InsurancePlan`/`InsurerAccountLink`/`User`/`InsuredPerson` 及其 Mapper 均已同步新列（commit `9fef17f`、
  `8c772e2`）。2026-07-24 复核时本机已安装 Maven（`/Users/madisonshen/.local/apache-maven-3.9.16`），
  执行 `cd java-backend && mvn -q compile` **编译通过（exit 0，无报错）**，此前"未完成"的状态已解除。
- **保司登录账号创建：已补齐独立 UI，不再是缺口。** `web/src/views/insurers/InsurerManagementView.vue`
  已包含"登录账号"管理入口（创建账号、启停、重置密码），管理员无需再手工建库记录。
- 生产部署（Render + xbbzp.com）与生产数据库迁移执行：未经用户明确授权前不得执行，需单独获得批准后再部署（CLAUDE.md）
