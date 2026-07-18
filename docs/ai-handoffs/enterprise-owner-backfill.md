# 存量企业主管标记数据愈合迁移

- task_id: `enterprise-owner-backfill`
- owner: `Claude Code`
- status: `merged-deployed`（用户 2026-07-18 授权豁免 PG 门槛并部署；合并提交 `61cde77`，push `7bc1001..61cde77` 触发 Render）
- branch: `fix/enterprise-owner-backfill`
- worktree: `/private/tmp/xbb-owner-backfill`
- base_commit: `7bc1001`（含已合并的 `enterprise-admin-owner-flag` 代码修复）
- migration_owner: `yes`（新迁移 `a1b2c3d4e5f6`，基于当前 head `27951ec2f8ee`）
- last_updated: `2026-07-18`

## 目标

代码修复 [enterprise-admin-owner-flag](enterprise-admin-owner-flag.md) 只阻止新增破损，不修正
此前经 `POST /api/enterprises/{id}/admins` 已建成、`is_owner=False` 的存量企业主账号。本任务用一次性
数据迁移愈合这些记录。

## 迁移内容

`backend/migrations_alembic/versions/a1b2c3d4e5f6_backfill_enterprise_owner_flag.py`（纯数据，无 DDL）：

- 对每个当前**没有任何 owner**的投保单位（其 `role='enterprise'` 用户中无 `is_owner` 且无
  `enterprise_role='owner'`），将**最早创建（最小 id）**的那个用户提升为 owner
  （`is_owner=sa.true()`、`enterprise_role='owner'`）。
- 已有 owner 的单位不动 → 天然幂等，可安全重放。
- 布尔值用 `sa.true()`，不用 `text("1")`（v4.2 Phase 2 教训）。
- `downgrade` 为 no-op 并注明理由：数据愈合不可精确回退，提升后的 owner 与本就正确的 owner
  无法区分，降级会重新弄坏单位。

## 验证

- `[x]` 单一 Alembic head：`python3 -m alembic heads` → `a1b2c3d4e5f6`。
- `[x]` SQLite 端到端回归 `tests/enterprise_owner_backfill_test.py`（用 create_all 建表 → stamp 前一
      head → `upgrade head` 只跑本迁移）：无主管单位愈合最早者、已有 owner 不动、第二管理员不被
      误提升、多破损只提升最小 id、重放幂等 —— 全部 PASS。
- `[~]` **强制 PG 门槛：经用户显式授权豁免（2026-07-18）**。本环境无 `NEON_API_KEY`
      （`~/.neon_api_key` 与环境变量皆无）、无本地 PostgreSQL/Docker，外部 Neon API 调用亦被沙箱
      拦截，`scripts/pg_migration_check.py` 无法执行。用户在知情下明确授权“豁免 PG 门槛”，放行合并。

### 豁免的风险论证（替代 PG 门槛的书面依据）

CLAUDE.md 的 PG 门槛源于 v4.2 Phase 2 教训：布尔列 `server_default` 用 `text("1")` 通过 SQLite
却被 PG 拒绝。本迁移**不触发该类风险**：

1. 纯数据迁移，无 DDL、无列默认值——不存在 server_default 类型陷阱。
2. 逐句 PG 兼容性复核：`is_owner OR enterprise_role='owner'`（bool OR bool）、
   `CASE WHEN <bool> THEN 1 ELSE 0 END`、`SUM(...)=0`、`MIN(id) GROUP BY ... HAVING`、
   参数化 `id IN (...)`、`sa.true()`——均为 PG 合法构造，无 SQLite 专有语义。
3. 布尔字面量用 `sa.true()`（PG 渲染为 `true`），正是教训要求的写法。
4. 安全网：迁移在生产容器启动时随 `alembic upgrade head` 在事务内执行；若在 PG 上出错则回滚、
   容器启动失败、Render 健康检查不通过 → **自动保留旧版本**，不会静默损坏数据。

## 风险

- “最早创建者即 owner”是与修复后开通逻辑一致的假设；若某单位历史上真正的负责人不是最早创建的
  账号，愈合结果需人工复核（属极少数边界，审计可追溯）。
- 后续若获得 Neon 凭据，建议补跑一次 `pg_migration_check.py` 作为回执（非阻塞）。
