# 业务员门户被全局守卫误挡热修复

- task_id: `salesperson-portal-access`
- owner: `Claude Code`
- status: `merged-deployed`（用户 2026-07-18 授权合并并部署；合并提交 `38936eb`，push `e3a02ab..38936eb` 触发 Render）
- branch: `fix/salesperson-portal-access`
- base_commit: `e3a02ab`
- migration_owner: `no（无迁移）`
- last_updated: `2026-07-18`

## 缺陷（生产级）

`backend/core/security.py` 的 `current_user` 有一个全局守卫，把 `salesperson` 账号限制在
精确匹配白名单 `SALESPERSON_ALLOWED_PATHS = {"/api/agents/me","/api/auth/me","/api/auth/password"}`。
Phase 5 上线了整套 `/api/agent-portal/*` 业务员工作台端点，却**没有把它们加进白名单**，于是业务员
对自己门户的每个请求都在到达路由自身门禁前就被 403（`业务员账号仅可访问业务员工作台相关接口`）。

**影响**：整个业务员门户（产品中心、余额、结算单、打款、佣金汇总/明细/导出）对业务员账号在生产
环境完全不可用。

## 为什么没被测出（测试盲区）

`tests/agent_portal_api_test.py` 只断言路由挂了依赖，并**直接调服务函数**校验一致性；
`agent_portal_leakage_test.py` 也走服务层。没有任何测试以业务员身份发**真实 HTTP 请求**，
所以 `current_user` 里的这条守卫从未被覆盖。

## 修复

在守卫中增加前缀放行 `SALESPERSON_ALLOWED_PREFIXES = ("/api/agent-portal/",)`：

```python
if user.role == "salesperson" and request.url.path not in SALESPERSON_ALLOWED_PATHS \
   and not request.url.path.startswith(SALESPERSON_ALLOWED_PREFIXES): raise HTTPException(403, ...)
```

用前缀而非精确集合，因为有带路径参数的 `/api/agent-portal/statements/{id}`。前缀下的每个端点
本身已有 `_SALESPERSON` 路由级门禁，故此放行不授予路由本身不会给的任何权限。已核对业务员唯一
另需的端点 `/api/agents/me` 已在白名单；`agents.py`/`reports.py` 其余 salesperson 语境均为 admin 侧。

## 验证（2026-07-18）

- `[x]` 新回归 `tests/salesperson_portal_access_test.py`（起真实 uvicorn，业务员 token 打门户）：
      修复前 6 个门户端点全 403，修复后全 200，且业务员访问 `/api/agents` 仍 403。
- `[x]` 全量 Python 回归 33 个测试文件全绿。
- `[x]` 实弹端到端：修复后代码起服务器，业务员 6 个门户端点返回 200+数据，admin 路径仍 403。

## 风险与阻塞

- 纯鉴权白名单放宽，范围限定在已各自加门禁的 `/api/agent-portal/*`，无越权面。
- 未经用户授权，不合并、不部署。

## 附带运维观察（非本任务代码问题）

排查时发现本机 8001 端口有一个**上个会话遗留的 uvicorn 进程（PID 35680，起于 2026-07-17 23:45）**，
跑的是修复前旧代码、连独立库，会误导本地手测。建议清理：`kill 35680`。我未擅自终止非本会话进程。
