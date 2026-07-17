# 跨运行时一致性与发布验收 v4.2 Phase 6

- task_id: `cross-runtime-release-phase6`
- owner: `Claude Code`
- status: `merged`（用户于 2026-07-18 授权合并；无冲突合并提交 `e1b9330`，功能提交 `c77dd8d`）
- branch: `feat/cross-runtime-parity-phase6`
- worktree: `/private/tmp/xiangbang-parity`
- base_commit: `33336e7`
- migration_owner: `no（本阶段不建迁移）`
- depends_on: `Phases 1-5（均已合并并发布生产）`
- last_updated: `2026-07-18`

## 目标

把 Java 运行时镜像补齐到与已合并 Python schema 与鉴权语义一致，用可执行的契约测试证明
两端字段级一致，跑完整 v4.2 回归，产出可发布的验收交接。

## Task 1：实际已合并内容盘点（替代计划文档中的推测清单）

### Alembic：单一 head，未变

```
27951ec2f8ee (head)  add agent settlements
  <- 7f0a1fa05267  add timeliness results
  <- c40dab695a66  add employment facts
  <- d5a4c12f7b91  add historical employer scopes（Phase 1，Java 已镜像）
  <- f7e2d9b1a4c8  add pending_terminations table（Java 未镜像，早于 v4.2，本阶段不补）
```

### Python 侧新增表（v4.2 Phase 2/3/5），Java 侧现状：**零镜像**

| 表 | 来源 Phase | Java entity | Java mapper | Java controller |
|---|---|---|---|---|
| `employment_feedback_batches` | 2 | 无 | 无 | 无 |
| `employment_facts` | 2 | 无 | 无 | 无 |
| `employment_fact_matches` | 2 | 无 | 无 | 无 |
| `integration_api_keys` | 2 | 无 | 无 | 无 |
| `integration_nonces` | 2 | 无 | 无 | 无 |
| `participation_operations` | 3 | 无 | 无 | 无 |
| `employment_timeliness_results` | 3 | 无 | 无 | 无 |
| `timeliness_outbox` | 3 | 无 | 无 | 无 |
| `agent_commission_statements` | 5 | 无 | 无 | 无 |
| `agent_commission_statement_items` | 5 | 无 | 无 | 无 |
| `agent_commission_payments` | 5 | 无 | 无 | 无 |
| `agent_commission_payment_allocations` | 5 | 无 | 无 | 无 |

对照：`user_employer_scopes`（Phase 1）已有 `UserEmployerScopeMapper.java` +
`EmployerScopeAccess.java` 的 fail-closed 过滤，是本阶段要照做的样板。

### Python 侧已上线端点：116 条（`openapi.json` 实测）

新增于 v4.2 且 Java 无对应 controller 的端点（共 23 条）：

```
/api/employment-facts, /api/employment-facts/unmatched,
/api/employment-facts/unmatched/{item_id}/match, /api/employment-facts/{item_id},
/api/employment-facts/{item_id}/correct, /api/employment-feedback/batches,
/api/employment-feedback/batches/{item_id}, /api/employment-feedback/import/confirm,
/api/employment-feedback/import/preview, /api/employment-feedback/template,
/api/integrations/employment-events, /api/integrations/employment-events/batch,
/api/integrations/employment-events/{source_event_id},
/api/timeliness/data-quality, /api/timeliness/details, /api/timeliness/export,
/api/timeliness/recalculate, /api/timeliness/summary,
/api/agent-portal/balances, /api/agent-portal/commissions/details,
/api/agent-portal/commissions/export, /api/agent-portal/commissions/summary,
/api/agent-portal/payments, /api/agent-portal/products,
/api/agent-portal/statements, /api/agent-portal/statements/{item_id}
```

### 范围决策（依据 `CLAUDE.md`："Java 后端是运行时镜像，只同步实体与 Mapper，不建立第二套迁移历史"）

给定 12 张新表、23 条新端点的体量，且 Java 历来并非对每个 Python 域都 1:1 建 controller
（例：`pending_terminations` 至今无 Java 镜像，历史交接文件已明确记录并接受），
本阶段确定的范围：

1. **实体 + Mapper：12 张新表全部镜像**，字段级与可空性完全对齐（Task 3）。
2. **契约测试**：Python 表结构 vs Java Mapper 的 `COLUMNS` 列表，逐列比对，
   且检查可空列在 Java 侧不得有默认值初始化（Phase 1 教训，Task 2）。
3. **Fail-closed 鉴权镜像：范围敏感的读取路径**——用工事实列表（项目负责人范围过滤）、
   及时率汇总（同上）、业务员佣金（agent_id 只能来自登录身份，不能来自请求参数）。
   新建最小化 controller 落地这三处读取，复用已验证的 `EmployerScopeAccess`。
4. **明确不做**：两阶段导入的完整写入流程、XLSX 导出、Outbox 定时处理、外部签名
   接入认证的完整重实现——这些是有状态的复杂业务流程，不是"镜像一致性"要解决的问题，
   且当前生产流量走 Python，Java 镜像的价值在于数据一致性与只读鉴权对齐，不在于
   重复实现全部可变更业务逻辑。此决定与范围偏离将在最终交接中显式记录。

## Active Phase 6 Scope

- `tests/cross_runtime_contract_test.py`：结构一致性契约测试
- `java-backend/src/main/java/com/xbb/baojing/employment/`：5 个 entity + mapper
- `java-backend/src/main/java/com/xbb/baojing/timeliness/`：3 个 entity + mapper
- `java-backend/src/main/java/com/xbb/baojing/agent/`：4 个 entity + mapper（追加到既有包）
- 三个最小化 fail-closed 读取 controller
- 完整 v4.2 回归矩阵 + 空库/旧库迁移验证

## 明确不做

- 新 Alembic 迁移（本阶段以 `python3 -m alembic heads` 结果不变为验收条件之一）。
- 两阶段导入、XLSX 导出、Outbox 调度、外部签名接入认证的 Java 完整重实现（见范围决策）。
- 未经用户授权的部署、生产密钥变更、小程序上传提交。

## Task 3/4：Java 镜像与 fail-closed 只读 controller（已完成）

12 张新表的 entity + Mapper 全部落地，字段级与可空性由契约测试逐列校验。三条范围敏感读取
路径以最小化 controller 镜像，鉴权全部复用 Phase 1 的 `EmployerScopeAccess`，不另立第二套：

- `EmploymentFactController` → `GET /api/employment-facts`：经 `EmploymentFactAccess`，
  只返回 `status='active'` 事实；项目经理限授权用工单位、admin 跨企业、其余角色 403。
- `TimelinessController` → `GET /api/timeliness/details`：经 `TimelinessResultAccess`，
  只返回 `status='current'` 结果，范围过滤与 `/summary` 共用同一边界。
- `AgentPortalController` → `GET /api/agent-portal/{statements,payments}`：仅 salesperson，
  `agent_id` 只取登录身份 `user.getId()`，不接受任何请求参数（§17.1）。

镜像修正（两处 WIP bug，均在本阶段发现并修）：`EmploymentFactMapper.findActiveScoped` 与
`EmploymentTimelinessResultMapper.findCurrentScoped` 原将 `enterprise_id = #{enterpriseId}`
写死，admin（enterpriseId 为 null）会退化成 `= NULL` 而永不命中；改为条件式，镜像 Python
「企业过滤只在 role=='enterprise' 时施加」。`EmploymentFactAccess` 原对 enterpriseId 为 null
一律 403 会误挡 admin，改为把 fail-closed 判定完全交给 `EmployerScopeAccess`。

## 验证（2026-07-18，均在最终提交状态上执行）

- `[x]` Java 编译：`mvn -o compile` BUILD SUCCESS（Maven 3.9.9，Temurin JDK 21）。
- `[x]` Java 单测：`mvn -o test` → Tests run: 8, Failures: 0, Errors: 0, Skipped: 0；
      含新增 `adminReadsAllActiveFactsAcrossEnterprises`（证明 admin 不再被误挡）。
- `[x]` 跨运行时契约测试：`python3 tests/cross_runtime_contract_test.py` 三项全过
      （逐列映射、可空列无 Java 侧默认值、check 约束状态值均有 Java 侧常量/注释）。
- `[x]` 完整 Python 回归：`tests/` 下 29 个测试文件全绿（模型/服务/引擎/导入/匹配/及时率/
      业务员门户/安全与系统 smoke 等）。smoke 类需 `web/dist`，本阶段不改前端，直接复用
      main 已构建产物（`web/` 与 main 无差异）。
- `[x]` 单一 Alembic head 未变：`python3 -m alembic heads` = `27951ec2f8ee`，本阶段零迁移。

## 风险与阻塞

- 本机默认无 Maven，验证时临时下载 apache-maven-3.9.9 至 `/tmp` 完成编译与单测；
  CI/其他代理需自备 Maven。此为环境事项，不影响代码。
- 按范围决策，Java 侧的及时率 `/summary` 比率聚合、业务员佣金明细/汇总/导出、两阶段导入与
  外部签名接入认证**未镜像**，生产读写流量仍走 Python。Java 镜像只保证数据结构一致与只读
  鉴权对齐；此偏离已在此显式记录。
- 未经用户授权，本阶段不合并、不部署、不改生产密钥、不上传小程序。
