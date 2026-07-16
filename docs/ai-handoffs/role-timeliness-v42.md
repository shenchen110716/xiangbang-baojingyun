# 角色分权、佣金结算与真实入离职及时率 v4.2

- task_id: `role-timeliness-v42`
- owner: `Claude Code`（自 Codex 接管，用户于 2026-07-16 明确授权；Codex 已停止该任务）
- status: `merged`（Phase 1 已合并并发布生产）
- branch: `codex/role-timeliness-v42-scope`
- worktree: `/private/tmp/xiangbang-role-v42-phase1`
- base_commit: `cf8fcced6d6a41167d1ae8389ce762ea83e4661e`
- migration_owner: `no（已释放；Phase 1 迁移 d5a4c12f7b91 已合并并在生产执行。迁移锁现由 employment-facts-phase2 持有）`
- depends_on: `recharge-accounts-phase-a、usage-lock-pending-termination（均已合并）`
- last_updated: `2026-07-16 11:05 AEST`

## 目标

实现业务员自助查看产品与本人佣金、企业项目负责人和实际工作单位多对多权限、真实入离职数据导入、参停保及时率计算，以及企业管理员按操作员查询和导出考核明细。

## 计划范围

- 业务员只能查看全部可售产品、平台最低销售价、本人佣金、本人结算和付款明细。
- 企业管理员维护项目负责人及其负责的实际工作单位。
- 项目负责人在 Web 和小程序中只能操作授权单位的参停保、岗位、理赔和看板。
- 企业管理员导入真实入职、离职时间，系统保留来源、版本和修正历史。
- 按产品生效规则计算及时参保、延迟参保、提前停保、延迟停保和未操作。
- 按操作员、实际工作单位、时间段、状态和原因查询与导出。

## 依赖状态

- `recharge-accounts-phase-a` 已合并。
- `usage-lock-pending-termination` 已由 Codex 审查修复并合并，迁移头为 `f7e2d9b1a4c8`。
- 合并后全量回归通过，当前无活动迁移所有者。
- 第一阶段独立分支和外部工作树最初从 `main@1c223e3` 创建，迁移所有权已申请。
- 待停保保障期/并发热修复已由 `cf8fcce` 完整纳入 `main`；本分支在完成 Task 5 提交 `dbf5716` 后，以无冲突合并提交 `a8f0216` 刷新到该基线。
- 热修复仅修改 `pending_terminations.py`、`policy_members.py`、`termination_scan.py`、`participation_lock_smoke.py` 及其交接；与 Phase 1 已提交文件无重叠，本任务没有手工修改这些文件。
- 最新已合并 Alembic head 仍为 `f7e2d9b1a4c8`；本分支唯一新 head `d5a4c12f7b91` 线性接在该 revision 之后。
- 业务员门户已由 `b664e20` 合并，当前没有活动业务员分支占用共享认证或 Web 文件。

## Active Phase 1 Scope

- 企业角色兼容字段与历史实际工作单位授权迁移。
- `UserEmployerScope` 模型、Schema、服务、路由和审计。
- 实际工作单位、岗位、参保、参停保批量及理赔的服务端数据范围过滤。
- 企业主管维护项目负责人授权的 Web 界面和项目负责人导航收敛。
- Java 模型、Mapper 和关键单位范围接口的语义同步；不建立第二套迁移。
- 明确不做：真实入离职事实、及时率计算、佣金结算付款、生产迁移、部署或小程序上传。

计划修改的公共区域包括 Alembic 最新迁移链、`backend/app.py`、模型/Schema/服务聚合入口、操作员与业务路由、Web 公共类型/认证状态/导航，以及对应 Java 模型和控制器。Phase 1 合并前，其他任务不得并行修改这些区域。

## 旧分支处置

- Claude 旧分支 `worktree-usage-lock-pending-termination@b63ff02` 在上一任务合并后新增了替代修复。
- Codex 已只读审查：该提交直接改写已合并迁移 `f7e2d9b1a4c8`，并回退已验证的 SPA 路由、保费账户和受影响人员展示，不能整包合并。
- 该分支不再持有迁移或公共模块所有权；其中原子通知去重等可取思路只能在未来单独立项、重新迁移并经 TDD 验证，不能覆盖当前主线。

## 设计进展

- 已完成 v4.2 产品与技术方案重新头脑风暴。
- 已确认月保单入职、离职反馈均允许 24 小时宽限；按天或即时产品不设宽限。
- 宽限期仅影响反馈及时率和责任解释，不改变保障结果主及时率。
- 已确认业务员可查看全部在售保险产品及平台最低售价，但只可查看本人佣金和收款。
- 完整独立设计基线：`SYSTEM-DESIGN-V4.2.md`。
- 设计已由用户确认；实施总路线图：`docs/superpowers/plans/2026-07-16-role-timeliness-v42-roadmap.md`。
- 第一阶段执行计划：`docs/superpowers/plans/2026-07-16-employer-scope-phase.md`。
- Phase 1 Task 1–5 已完成：红灯安全契约、历史授权迁移、集中式 scope 服务、授权管理 API，以及实际单位/岗位/参保/理赔/看板/参保导出的服务端范围强制。

## Phase 1 实施进展

- `c4d45e2` — 定义项目负责人实际工作单位授权安全契约。
- `743e5b1` — 增加企业角色兼容字段与历史授权迁移 `d5a4c12f7b91`。
- `4a8cc3e` — 集中实现 scope 查询、校验、授权、撤销和主要负责人替换。
- `8c61ea7` — 增加授权管理 API、operator `enterprise_role` 和审计。
- `dbf5716` — 在实际单位、岗位、参保、理赔、看板和参保名单中强制 fail-closed 范围。
- `a8f0216` — 无冲突刷新到包含保障期/并发热修复的 `main@cf8fcce`。
- `b0e8450` — 增加企业主管授权实际工作单位 UI、主要负责人替换入口及项目负责人导航收敛。
- Task 5 验证：`employer_scope_smoke.py`、`security_smoke.py`、`system_smoke.py`、`recharge_smoke.py` 均通过；当前系统 Python 未安装 `pytest`，focused pytest 待可用环境补跑。
- Task 6 验证：`web/npm run build` 与 `employer_scope_smoke.py` 均通过。
- Task 7（由 Claude Code 接管完成）：Java 运行时镜像同步 `enterprise_role`、`UserEmployerScope` 模型/Mapper、`EmployerScopeAccess` 门禁，以及实际单位、岗位、理赔控制器的 fail-closed 过滤。未新建 Java 迁移，Alembic 仍为唯一权威。

## 接管说明（Codex → Claude Code）

Codex 在 Task 7 中途停止，工作树遗留未提交且**无法编译**的 Java 改动。接管后修复两处：

- 编译中断：`ClaimController.create()` 调用了并不存在的 `claimService.requirePersonScope(...)`，控制器同时缺少 `WorkPosition` 导入与 `positionMapper` 字段。改为在 `ClaimService` 内实现 `requirePersonScope(User, InsuredPerson)`，由服务自身完成人员→岗位→用工单位解析；`claimAccess()` 复用同一方法，控制器无需新增导入或字段。
- fail-open 缺陷：`User.enterpriseRole` 原初始化为 `"owner"`。MyBatis 默认 `callSettersOnNulls=false`，`enterprise_role` 为 NULL 时不调用 setter，字段保留 `"owner"`，该用户将被判定为企业主管并取得全企业数据访问权；而 Python 权威实现对同一用户返回 403。已改为可空默认（与 `backend/models/user.py` 的 `nullable=True` 一致），并新增回归测试 `enterpriseUserWithoutEnterpriseRoleFailsClosed`。当前 Python 建号路径始终写入该字段（seed 写 `owner`、`routers/operators.py` 写 `project_manager`），故该缺陷为潜伏风险而非线上已触发问题。

## Phase 1 验收矩阵（2026-07-16，Claude Code 执行）

- `[x]` `python3 tests/employer_scope_smoke.py`
- `[x]` `python3 tests/employer_scope_model_test.py`、`tests/employer_scope_service_test.py`
- `[x]` `python3 tests/security_smoke.py`
- `[x]` `python3 tests/system_smoke.py`
- `[x]` `python3 tests/recharge_smoke.py`
- `[x]` `python3 tests/participation_lock_smoke.py`
- `[x]` `python3 tests/salesperson_portal_smoke.py`
- `[x]` `web/npm run build`
- `[x]` `java-backend` Maven `test`：3 tests / 0 failures（Maven 未在 PATH，实际路径 `~/Library/ApacheMaven/apache-maven-3.9.16/bin/mvn`）
- `[x]` `alembic heads` 单一 head `d5a4c12f7b91`
- `[x]` `python3 -m compileall -q backend`、`git diff --check`

## 合并与发布（2026-07-16，用户明确授权「合并，推送并部署」）

- 合并提交：`9b988bd`（`Merge branch 'codex/role-timeliness-v42-scope'`）。唯一冲突为 `recharge-accounts-phase-a.md` 交接文档，两侧描述同一已合并事实，已取 `main` 版并并入 Codex 的 `merge-base` 核验记录。
- 合并后在 `main` 上重跑全量门槛：8 项 Python 烟测/单测、`web/npm run build`、Maven 3 tests、Alembic 单头 `d5a4c12f7b91`、`compileall`、`git diff --check` 全部通过。
- 推送：`717e1a9..9b988bd`，本次为统一发布窗口，一并发布充值账户、使用费锁定/待确认停保、保障期权威热修复与 v4.2 Phase 1，共 39 个提交 / 67 文件。
- 部署：`render.yaml` 配置 `autoDeployTrigger: commit`，推送即自动部署，无独立部署命令。Dockerfile 启动命令为 `alembic upgrade head && uvicorn ...`。
- 生产验证：`/api/health` 返回 200；`/api/employer-scopes` 由 404 转为 401（路由存在并正确要求鉴权），`openapi.json` 含 90 条路由且覆盖 employer-scopes、pending-terminations、recharge。迁移失败则容器无法启动，新路由正常服务即证明 `d5a4c12f7b91` 已在生产 PostgreSQL 成功执行。

## 已知风险与阻塞

- Java 侧仅有 `EmployerScopeAccess` 的单元测试，控制器层过滤无集成测试覆盖；Java 为运行时镜像，权威语义与回归以 Python 为准。
- 系统未安装 `pytest`，计划中的 focused pytest 仍待可用环境补跑；现有 `*_test.py` 以独立脚本方式运行并通过。
- 本任务未上传或提交微信小程序。
- Phase 1 迁移锁已释放，Phase 2 可基于 `main@9b988bd` 创建新分支与迁移，新迁移须线性接在 `d5a4c12f7b91` 之后。
- 生产已执行 `d5a4c12f7b91`；该迁移此后只可追加修正，不可改写。

## 依赖解除条件

- `[x]` `recharge-accounts-phase-a` 状态为 `merged`。
- `[x]` `usage-lock-pending-termination` 状态为 `merged`。
- `[x]` `main` 已通过合并后回归。
- `[x]` 新任务分支以更新后的 `main` 为基线。
- `[x]` 新 Alembic 迁移 `d5a4c12f7b91` 线性接在最新迁移头 `f7e2d9b1a4c8` 之后。

## 后续实施顺序

1. 数据范围服务和项目负责人关系。
2. 真实入离职事实、导入预览和版本记录。
3. 参停保操作事实和及时率计算引擎。
4. 操作员考核查询与导出。
5. Web 与小程序项目负责人界面。
6. 业务员产品、佣金结算和付款明细门户。
7. 三端权限、统计和回归测试。
