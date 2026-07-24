# 保险公司独立工作台 — 交接记录

- 状态：已完成实现，待合并
- 分支：`worktree-insurer-portal`
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

## 风险与后续
- **PostgreSQL 迁移验证：未完成，非本任务代码问题。** 本执行环境没有 `NEON_API_KEY` 环境变量，也没有
  `~/.neon_api_key` 文件，`python3 scripts/pg_migration_check.py` 直接报错退出（"缺少 Neon API key"），
  无法联网创建 Neon throwaway branch 来跑迁移。这与 Task 1 Step 5 遇到的情况完全一致——当时同样因为缺少
  凭据未能验证。**这是一个未解决的验证缺口**：迁移文件本身（`b4f19a7d2e63_add_insurer_entity.py`）在
  SQLite 上已反复验证通过（本任务及此前所有任务的测试全部使用 SQLite），但按 CLAUDE.md 的明确要求，
  SQLite 通过和离线 SQL 生成都不足以证明 PostgreSQL 可用（同一文件举了 v4.2 Phase 2 布尔列默认值的真实
  事故案例）。**在获得 Neon 凭据并成功跑通 `pg_migration_check.py` 之前，不应认为此迁移已在生产环境验证。**
  下一位有权限的代理/人工需要设置 `NEON_API_KEY` 后重跑此脚本，确认无误后才能合并到会触达生产的分支。
- **Java 镜像后端同步：未完成，标记为后续任务。** 本执行环境没有安装 Maven（`which mvn` 无输出，
  `mvn -version` 报 command not found），因此不具备"写 Java 代码 + 用 `mvn -q compile` 验证"的条件。
  按本任务 brief Step 4 的兜底指示（执行环境缺 Maven 时不要静默跳过，也不要写未经编译验证的推测性 Java
  代码），这里明确将其列为未验证的后续工作，而不是去凭空写一份没有编译器验证过的 `Insurer.java`。
  已定位到需要同步的镜像文件（现状确认，未修改）：
  - `java-backend/src/main/java/com/xbb/baojing/plan/InsurancePlan.java` + `InsurancePlanMapper.java`（需加 `insurer_id` 字段/映射）
  - `java-backend/src/main/java/com/xbb/baojing/recharge/InsurerAccountLink.java` + `InsurerAccountLinkMapper.java`（需加 `insurer_id` 字段/映射）
  - `java-backend/src/main/java/com/xbb/baojing/common/User.java` + `UserMapper.java`（需加 `insurer_id` 字段/映射）
  - `java-backend/src/main/java/com/xbb/baojing/insured/InsuredPerson.java` + `InsuredPersonMapper.java`（需加 `insurer_flag_reason`/`insurer_flagged_at`/`insurer_flagged_by` 等标注列）
  - 尚不存在与新 `backend/models/insurer.py`（`Insurer` 实体）对应的 Java 实体/Mapper（例如
    `com.xbb.baojing.insurer.Insurer` + `InsurerMapper`），需要新建，同包结构与命名习惯参照
    `InsurancePlan`/`InsurancePlanMapper`。
  下一位有 Maven 环境的代理/人工需要：新建 `Insurer` 实体 + Mapper，给上述四个已存在的镜像类加对应新列，
  然后跑 `cd java-backend && mvn -q compile` 验证编译通过，再更新本节状态。
- 生产部署（Render + xbbzp.com）与生产数据库迁移执行：未经用户明确授权前不得执行，需单独获得批准后再部署（CLAUDE.md）
- 保司账号的首个真实测试账号需管理员通过"保司主体管理"页面创建 Insurer 记录后，再手工创建一个 role='insurer' 的 User 并关联 insurer_id（本计划未包含"平台端创建保司账号"的独立 UI——如需要，是后续一个小任务：在现有"单位账号管理"模式基础上加一个 insurer 账号创建入口）
