# 真实用工事实与两阶段导入 v4.2 Phase 2

- task_id: `employment-facts-phase2`
- owner: `Claude Code`
- status: `active`
- branch: `feat/employment-facts-phase2`
- worktree: `/private/tmp/xiangbang-employment-facts`
- base_commit: `8bc04e0`
- migration_owner: `yes（Phase 2 独占）`
- depends_on: `role-timeliness-v42 Phase 1（已合并发布）`
- last_updated: `2026-07-17`

## 目标

存储按人版本化的真实入离职事实，经由原子的两阶段预览/确认导入写入，附带身份证保护，
并提供带认证的外部用工事件接口——为 Phase 3 的及时率计算提供权威事实基线。

## 执行计划

`docs/superpowers/plans/2026-07-17-employment-facts-phase.md`（9 个任务）。

## Active Phase 2 Scope

- `employment_feedback_batches` / `employment_facts` / `employment_fact_matches` 迁移与模型
- `backend/core/id_number.py` 身份证密文与确定性哈希
- `backend/services/employment_facts.py`、`employment_matching.py`、`employment_import.py`
- `backend/routers/employment_facts.py` 与 `backend/routers/integrations.py` 外部事件认证
- 新依赖 `cryptography` 与新生产密钥 `ID_ENCRYPTION_KEY`（含 `render.yaml`）

## 明确不做

- Java 镜像（Phase 6 负责）。
- Web 与小程序界面（Phase 4 负责）。
- 及时率计算（Phase 3 负责）。
- 存量数据回填：迁移只建空表，不从 `InsuredPerson` 伪造真实入离职时间（§16）。

## 迁移

- 基线唯一 head：`d5a4c12f7b91`（Phase 1）。本阶段新迁移的 `down_revision` 必须正是该 revision。
- 本阶段独占迁移锁；合并并释放前，其他任务不得创建新迁移。

## 部署影响（需用户操作）

本阶段新增必需生产密钥 `ID_ENCRYPTION_KEY` 与依赖 `cryptography`。
`render.yaml` 会增加该 key 的声明（`sync: false`），但**密钥值必须由用户在 Render 后台设置**，
且必须在本阶段部署前完成，否则 `verify_production_config()` 会阻断生产启动。
代理不得自行设置生产密钥。

## 验证

待 Task 9 阶段门槛填写。

## 风险与阻塞

- 待填写。
