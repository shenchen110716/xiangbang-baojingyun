# 小程序端功能安全审计与修复

- task_id: `miniprogram-security-audit`
- owner: `Claude Code`
- status: `review`（修复完成并通过全量回归，待用户授权合并/部署）
- branch: `fix/miniprogram-security-audit`
- base_commit: `58ea67f`
- migration_owner: `no（无迁移）`
- last_updated: `2026-07-24`

## 背景

对 `miniprogram/` 全量 28 个页面做了一次功能审计（分 5 组并行核对前端与对应后端 API 的一致性），
产出 5 项高危、9 项中危、5 项低危问题。本任务分两批修完全部可修项，详见下方清单。
主动跳过 2 项：全线补分页、导入页支持 xlsx 客户端预检——量级是功能/架构工作，不算"修复"，
建议另开任务。

## 修复清单

### 高危（commit `cb3c7a4`）

1. **登录页硬编码暴露生产测试账号**：`login.wxml` 明文展示 `enterprise/enterprise123`，且该账号
   在生产环境同样由 `seed.py` 自动创建。已移除展示。
2. **`apiBase` 可被终端用户任意篡改，构成钓鱼入口**：登录页/设置页此前可在登录前把服务地址改到
   任意域名，账号密码会直接发过去。改为仅 `wx.getAccountInfoSync().envVersion !== 'release'`
   （开发/体验版）可见可改；`setApiBase` 加了同样的服务端防线；切换服务地址会清掉已登录 token。
3. **`GET /plan-tiers` 泄露内部成本价**：企业角色能看到平台原始成本价（`price` 等字段），
   `/plans` 早就做了同样的过滤但 `/plan-tiers` 漏了；顺带修了它的企业可见范围（此前只算佣金关系，
   漏了"仅通过岗位关联"的产品）。新增 `tests/plan_tiers_pricing_test.py`。
4. **`GET /payments` 是纯管理员端点**：企业微信支付成功后，在小程序"充值记录"里完全查不到这笔单
   （查的是另一张表）。开放给企业角色（限定只能看自己单位的），小程序端合并展示两条记录线。
   `tests/wechat_pay_smoke.py` 补了越权/范围断言。
5. **临时日结模式下"生效时间"选了不生效**：日期选择器没跟着模式隐藏，选的值提交时被静默丢弃。
   加了 `wx:if` 守卫和说明文案。

### 中低危（commit `ef9f323`）

- 企业/用工单位列表编辑删除按钮补了 `canManage` 角色守卫（原审计标为高危，首次汇总时误分类，
  这批一并按高优先级处理）
- 修改密码从明文 `wx.showModal` 换成新建的掩码输入页 `pages/change-password`
- 岗位详情 403/越权时退出页面，不再显示能点上传的半成品表单；状态标签统一走 `app.statusText()`
- 员工详情身份证号打码，跟列表页口径一致
- 批量导入失败时清掉旧的"预检成功"绿色提示
- 理赔详情页补了内部 SLA 超期/风险提示（数据早就在 API 响应里，只是没渲染）
- 参保证明对非在保人员加了醒目提示，避免被当有效证明截图使用
- `GET /billing` 补传每个保费账户的 `insurers`，充值页据此精确路由到用户点的那张账户卡片
- 微信支付成功后轮询确认到账再提示，减少展示旧余额的时间窗口
- 清理死代码 `app.ensureLogin()`、首页仪表盘一个跟真实接口对不上的占位字段名、
  实际用工单位编辑页标题固定显示"新增"的问题

## 允许修改范围

`miniprogram/` 全目录、`backend/routers/plans.py`、`backend/routers/payments.py`、
`backend/routers/reports.py`（仅 `/billing` 补字段）、对应测试文件。未改数据库模型/迁移、
RBAC 核心逻辑、Java 镜像。

## 验证（2026-07-24）

- `[x]` 两批改动各自跑了 `tests/` 全部 39 个测试文件；新增/扩展了 3 个测试文件
  （`plan_tiers_pricing_test.py` 新增，`wechat_pay_smoke.py`、`recharge_smoke.py` 扩展）
- `[x]` 6 项测试失败逐一用 `git stash` 跟改动前的 `main` 比对，确认是本机环境问题
  （沙盒禁止绑定本地端口跑 HTTP 级测试；SQLite 不支持某几个 Alembic 约束操作），
  不是本次改动引入的回归
- `[x]` `python3 -m compileall -q backend` 通过；`git diff --check` 无空白问题
- `[x]` 逐文件人工复核了这条分支改动的全部 14 个 JS 文件 + 全部 wxml 文件的 diff
  （本机没有 node/wx 开发者工具，无法自动语法检查或真机跑）；复核中发现并修正一处
  范围外的副作用——`settings.wxml` 把整张卡片塞进 `isDevEnv` 判断，连累"测试连接"
  按钮在正式版也不可用了，已收窄为只锁编辑框（commit `dc3e6b0`）
- `[ ]` 仍未做微信开发者工具/真机走查——本机无法安装/运行微信开发者工具，人工复核
  只能覆盖到"代码逻辑自洽"，覆盖不到真实渲染/交互效果，合并前必须由能访问开发者工具
  的人过一遍

## 风险与阻塞

- 未经用户授权，不合并到 `main`、不部署、不改生产密钥、不提交小程序审核
- 建议合并前至少在微信开发者工具里过一遍改动到的页面（登录、apiBase 开发版开关、
  充值记录、岗位详情、参保证明、修改密码新页面），本次没有做前端走查
