# 存量企业主管标记数据愈合迁移

- task_id: `enterprise-owner-backfill`
- owner: `Claude Code`
- status: `blocked`（迁移已写并通过 SQLite 验证；**阻塞于强制 PG 门槛**，本环境无 Neon 凭据，不可合并）
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
- `[ ]` **强制 PG 门槛未完成**：`python3 scripts/pg_migration_check.py` 需 `NEON_API_KEY`
      （环境变量或 `~/.neon_api_key`），本环境两者皆无，且外部 Neon API 调用被沙箱拦截。
      **按 CLAUDE.md 硬约束，未过 PG 验证不得合并本迁移。**

## 解除阻塞的方式（二选一）

1. 提供 `NEON_API_KEY`（放到 `~/.neon_api_key` 或环境变量），并允许脚本访问 Neon API，我来跑
   `python3 scripts/pg_migration_check.py`，通过后合并 + 部署（部署即容器启动时 `alembic upgrade head`
   自动执行本迁移）。
2. 你自行在具备 Neon 凭据的环境跑一次 `pg_migration_check.py`，通过后授权我合并。

## 风险

- 本迁移在生产容器启动时随 `alembic upgrade head` 执行一次；执行前务必确认 PG 门槛通过。
- “最早创建者即 owner”是与修复后开通逻辑一致的假设；若某单位历史上真正的负责人不是最早创建的
  账号，愈合结果需人工复核（属极少数边界，日志/审计可追溯）。
- 未经用户授权，不合并、不部署。
