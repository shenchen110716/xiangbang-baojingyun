# 角色分权、佣金结算与真实入离职及时率 v4.2

- task_id: `role-timeliness-v42`
- owner: `Codex`
- status: `planned`
- branch: `待创建：feat/role-timeliness-v42-phase1`
- worktree: `待创建，必须位于主仓库外`
- base_commit: `使用费锁定/待停保合并后的 main（f2632f5 之后）`
- migration_owner: `no（创建独立分支后申请）`
- depends_on: `recharge-accounts-phase-a、usage-lock-pending-termination（均已合并）`
- last_updated: `2026-07-16 10:16 AEST`

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
- 下一步可按第一阶段计划创建独立分支和工作树，再将状态改为 `active`、申请迁移所有权。

## 设计进展

- 已完成 v4.2 产品与技术方案重新头脑风暴。
- 已确认月保单入职、离职反馈均允许 24 小时宽限；按天或即时产品不设宽限。
- 宽限期仅影响反馈及时率和责任解释，不改变保障结果主及时率。
- 已确认业务员可查看全部在售保险产品及平台最低售价，但只可查看本人佣金和收款。
- 完整独立设计基线：`SYSTEM-DESIGN-V4.2.md`。
- 设计已由用户确认；实施总路线图：`docs/superpowers/plans/2026-07-16-role-timeliness-v42-roadmap.md`。
- 第一阶段执行计划：`docs/superpowers/plans/2026-07-16-employer-scope-phase.md`。
- 本轮仅更新设计和交接，不创建迁移或修改业务代码。

## 依赖解除条件

- `[x]` `recharge-accounts-phase-a` 状态为 `merged`。
- `[x]` `usage-lock-pending-termination` 状态为 `merged`。
- `[x]` `main` 已通过合并后回归。
- `[ ]` 新任务分支以更新后的 `main` 为基线。
- `[ ]` 新 Alembic 迁移接在最新迁移头 `f7e2d9b1a4c8` 之后。

## 后续实施顺序

1. 数据范围服务和项目负责人关系。
2. 真实入离职事实、导入预览和版本记录。
3. 参停保操作事实和及时率计算引擎。
4. 操作员考核查询与导出。
5. Web 与小程序项目负责人界面。
6. 业务员产品、佣金结算和付款明细门户。
7. 三端权限、统计和回归测试。
