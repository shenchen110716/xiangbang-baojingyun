# 企业开通管理员未置主管标记热修复

- task_id: `enterprise-admin-owner-flag`
- owner: `Claude Code`
- status: `review`（修复完成并通过全量回归，待用户授权合并/部署）
- branch: `fix/enterprise-admin-owner-flag`
- worktree: `/private/tmp/xbb-owner-fix`
- base_commit: `e9dc465`（已变基到含 Phase 6 的最新 main）
- migration_owner: `no（无迁移）`
- last_updated: `2026-07-18`

## 背景与承接

本修复此前作为**未提交的 WIP** 遗留在 `xbb-owner-fix` 工作树中（分支停在 33336e7，无提交）。
2026-07-18 用户在清理已合并工作树时授权接手完成。经确认：无其他活跃任务占用 `enterprises.py`，
无文件重叠，可串行处理。

## 缺陷

`POST /api/enterprises/{id}/admins`（平台为投保单位开通其管理员账号，Web `EnterprisesPanel`
的主路径）创建的 `role='enterprise'` 用户既没有 `is_owner=True` 也没有 `enterprise_role='owner'`。
权威判定 `is_enterprise_owner()`（`backend/services/employer_scopes.py`）随之对该账号返回 False，
级联导致：

- 操作员管理被拒（`仅单位主管可管理操作员` → 403）；
- 所有按用工单位范围的读取（投保岗位、在保人员、用工事实、及时率）都按“无授权范围的项目经理”
  过滤，等于该企业主看不到自己单位的数据。

因这是开通主路径，Phase 1 上线后经该 API 新建的每个企业主账号都“名存实亡”。

## 修复

`backend/routers/enterprises.py::add_enterprise_admin`：为该企业创建的**第一个**管理员置
`is_owner=True` / `enterprise_role='owner'`；此后再创建的管理员是项目经理
（`is_owner=False` / `enterprise_role='project_manager'`）——每企业一个在册主管，与 `seed.py`
的不变量及“单一主责经理”设计一致。返回体追加 `is_owner`、`enterprise_role`（向后兼容，纯新增字段）。

对照确认其余创建 enterprise 用户的路径均已正确，无需同改：`operators.py`（项目经理，
`is_owner=False`）、`seed.py`（owner=True）、`core/migrations.py`（从 `is_owner` 回填 `enterprise_role`）。

## 允许修改范围

仅 `backend/routers/enterprises.py` 的开通管理员分支及新增测试。不改数据库迁移、RBAC 模型、
Web、小程序或 Java。

## 验证（2026-07-18，均在变基后最终提交上执行）

- `[x]` 新回归 `tests/enterprise_admin_owner_test.py`：撤补丁时在 `assert owner.is_owner is True`
      处失败（`is_owner=False`），打补丁后 PASS，且第二个管理员 `is_owner=False` —— 证明真回归。
- `[x]` 全量 Python 回归：`tests/` 30 个测试文件全绿（含安全/权限、用工单位范围、
      业务员门户、系统与安全 smoke）。
- `[x]` 跨运行时契约测试通过；`python3 -m compileall -q backend` 通过；`git diff --check` 无空白问题。

## 风险与阻塞

- **存量数据未愈合**：本修复只阻止新增破损，不修正在此之前经 API 创建、已 `is_owner=False`
  的存量企业主账号。若生产存在此类记录，需要一次性数据订正（一条 UPDATE：将每个企业中最早创建、
  当前无 owner 的 `role='enterprise'` 账号提升为 owner）。此项涉及迁移/数据变更，按协议需真实
  PostgreSQL 验证，**未纳入本次范围**，交用户决定是否另开任务。
- 未经用户授权，不合并、不部署、不改生产密钥、不上传小程序。
