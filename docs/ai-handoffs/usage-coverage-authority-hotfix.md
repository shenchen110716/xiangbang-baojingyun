# 待确认停保保障期权威与并发确认热修复

- task_id: `usage-coverage-authority-hotfix`
- owner: `Codex`
- status: `merged`
- branch: `codex/usage-coverage-authority`
- worktree: `/private/tmp/xiangbang-usage-coverage-hotfix`
- base_commit: `1c223e3`
- migration_owner: `no`
- depends_on: `usage-lock-pending-termination（已合并）`
- last_updated: `2026-07-16`

## 目标与范围

修复已合并待确认停保流程的两个边界：以当前实际有效的 `PolicyMember` 保障期而非 `InsuredPerson.policy_id`/岗位产品判断欠费账户影响范围；使用数据库条件更新原子抢占确认权，保证 SQLite 与 PostgreSQL 下并发确认最多执行一次。

仅修改待停保查询、确认路由、保单保障期终止助手及其烟测；不修改数据库迁移、v4.2 角色/及时率模型、Web、小程序或 Java。与活动任务 `role-timeliness-v42` 已约定文件隔离。

## 实现

- 欠费影响范围关联当前时刻已生效且尚未到期的最新 `PolicyMember`，再由其保单产品映射保司账户。
- 临时日保单即使人员存储状态已为 `stopped`、`policy_id` 已清空，只要保障期仍有效仍会进入停保范围并立即截断保障期。
- 未来才生效的新保障记录不会覆盖当前有效保障记录。
- 确认接口先执行 `pending -> processing` 条件更新；只有 `rowcount == 1` 的请求继续，重复/并发请求返回 400，不存在返回 404。

## 验证

- `[x]` `python3 tests/participation_lock_smoke.py` 连续 3 次通过（含双会话并发确认、临时日保、未来保单、账户隔离）。
- `[x]` `python3 tests/recharge_smoke.py`。
- `[x]` `python3 tests/security_smoke.py`。
- `[x]` `python3 tests/system_smoke.py`。
- `[x]` `python3 tests/salesperson_portal_smoke.py`。
- `[x]` `python3 -m compileall -q backend` 与 `git diff --check`。
- `[x]` `web/npm run build`。

## 风险与下一动作

- 无迁移，不占用 v4.2 迁移锁。
- 独立审查发现强制停保会按最大 ID 误选未来保障期；现由范围扫描返回并传递精确 `PolicyMember.id`，生命周期函数不再按稍后的时间重新猜测。竞态测试模拟扫描后未来记录刚好生效，断言仍只终止扫描时选中的当前保障期。
- 已补充确认抢占后注入异常的回滚测试；新会话确认任务恢复为 `pending`。并发测试在 SQLite 双连接通过，PostgreSQL 依赖正常条件更新事务语义，本地未配置 PostgreSQL 实例。
- 最终独立复审：无 P0/P1；仅保留“未在真实 PostgreSQL 双连接环境运行集成测试”的 P2 覆盖缺口，代码审查确认条件更新的单赢家语义成立。
- 已快进合并本地 `main@fa1361e`，并已通知 v4.2 子代理在当前小任务提交后刷新基线。
