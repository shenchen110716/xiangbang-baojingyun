# 响帮帮保经云 v3.0 测试文档及操作说明

## 1. 测试范围

本系统包含三个端：

- 平台端：运营、产品、财务、保司、业务员和理赔管理。
- 投保单位电脑端：企业/HR 的员工、岗位、参停保、保单、资金和理赔操作。
- 微信小程序端：移动员工维护、批量导入、参停保发送、资金和理赔材料上传。

后端地址默认为 `http://127.0.0.1:8001`，接口前缀为 `/api`。

## 2. 启动系统

```bash
cd /Users/madisonshen/Desktop/Demo
python3 -m pip install -r requirements.txt
python3 -m uvicorn backend.app:app --host 127.0.0.1 --port 8001
```

浏览器访问：

- 平台端：`http://127.0.0.1:8001/?autologin=1&portal=admin`
- 用户端：`http://127.0.0.1:8001/?autologin=1&portal=enterprise`
- OpenAPI 文档：`http://127.0.0.1:8001/docs`

默认账号：

| 端 | 账号 | 密码 |
|---|---|---|
| 平台端 | `admin` | `admin123` |
| 投保单位端 | `enterprise` | `enterprise123` |

## 3. 环境配置

开发测试保持：

```env
INTEGRATION_MODE=mock
```

此模式不会真实发送短信、邮件、保司请求或支付请求，只返回模拟请求编号。

生产联调时复制 `.env.example` 为 `.env`，设置：

```env
INTEGRATION_MODE=real
INSURER_API_BASE_URL=https://...
SMS_PROVIDER_URL=https://...
EMAIL_PROVIDER_URL=https://...
PAYMENT_PROVIDER_URL=https://...
```

真实密钥不得提交到 Git、前端代码或小程序包。

## 4. 平台端操作说明

### 4.1 投保单位

1. 进入“投保单位管理”。
2. 点击“新增投保单位”。
3. 填写单位名称、类型、联系人、电话。
4. 设置平台使用费单价，默认 `0.1 元/人/天`。
5. 设置余额预警提前天数，范围 `3–7 天`。
6. 在“账号管理员”中创建单位管理员。
7. 在“参保产品”中查看该单位可用方案。

### 4.2 保险产品方案

每个方案必须配置：

- 保险公司
- 方案名称
- 基础价格
- 职业类别和子类别价格
- 计费模式：按月/按天
- 生效模式：次日生效/即时生效
- 返佣比例和支付模式

参保单位端不能维护产品，只能使用平台分配的方案。

### 4.3 业务员与佣金

- 一个投保单位只能关联一个业务员。
- 一个业务员可以为同一单位关联多个产品方案。
- 佣金支持返佣和加价两种模式。
- 佣金关系可编辑和删除。

### 4.4 经营大屏

检查以下指标：

- 参保员工数
- 参保单位数
- 产品数和累计保费
- 保费账户余额
- 平台使用费余额
- 预计日消耗
- 余额预计耗尽天数
- 3–7 天余额预警

## 5. 用户电脑端操作说明

用户端仅显示本单位数据，不能访问：

- 业务员管理
- 保险产品管理
- 保险公司方案
- 推广与佣金

用户端可操作：

- 用工单位和岗位
- 参保员工新增、编辑、批量导入
- 参保/停保名单发送
- 保单查看
- 保费账户和平台使用费账户充值
- 发票申请
- 理赔报案和材料上传

## 6. 微信小程序操作说明

使用微信开发者工具导入：

```text
/Users/madisonshen/Desktop/Demo/miniprogram
```

真机或开发者工具预览前，将 `miniprogram/app.js` 的 `apiBase` 改为 HTTPS 后端地址。

主要页面：

- 首页
- 参保员工
- 员工编辑
- 批量导入
- 参停保批次
- 平台产品
- 资金与发票
- 理赔材料

## 7. 核心功能测试用例

### 7.1 登录与权限

| 编号 | 操作 | 预期 |
|---|---|---|
| AUTH-01 | admin 登录 admin 门户 | 成功进入平台端 |
| AUTH-02 | enterprise 登录 enterprise 门户 | 成功进入用户端 |
| AUTH-03 | enterprise 访问 `/api/agents` | 返回 403 |
| AUTH-04 | 用户端查看其他单位员工 | 返回 403 或不返回数据 |
| AUTH-05 | 错误密码登录 | 返回 401 |

### 7.2 产品方案

| 编号 | 操作 | 预期 |
|---|---|---|
| PLAN-01 | 新增按月、次日生效方案 | 保存成功 |
| PLAN-02 | 新增按天、即时生效方案 | 保存成功 |
| PLAN-03 | 用户端请求 `/api/plans` | 只返回平台分配方案 |
| PLAN-04 | 删除已被保单使用方案 | 返回 409，不允许删除 |

### 7.3 业务员唯一关联

| 编号 | 操作 | 预期 |
|---|---|---|
| AGENT-01 | 单位关联业务员 A | 成功 |
| AGENT-02 | 同单位同业务员关联第二个产品 | 成功 |
| AGENT-03 | 同单位改关联业务员 B | 返回 409 |
| AGENT-04 | 用户端打开业务员管理 | 菜单隐藏且接口 403 |

### 7.4 参停保发送

| 编号 | 操作 | 预期 |
|---|---|---|
| ENR-01 | 选择单位、方案发送参保 | 返回 request_id 和人数 |
| ENR-02 | 发送停保 | 返回停保 request_id |
| ENR-03 | 发送邮件名单 | 返回邮件请求编号 |
| ENR-04 | 发送短信通知 | 返回短信请求编号 |
| ENR-05 | 外部服务失败 | 显示失败原因，可重试 |

### 7.5 资金、支付与对账

| 编号 | 操作 | 预期 |
|---|---|---|
| PAY-01 | 创建保费支付订单 | 状态 pending，生成订单号 |
| PAY-02 | 创建平台使用费订单 | account=usage |
| PAY-03 | 重复支付回调 | 不重复入账 |
| PAY-04 | 查询平台对账 | 返回 pending/paid/failed 统计 |
| PAY-05 | 用户端充值 | 只影响本单位对应账户 |

### 7.6 理赔

| 编号 | 操作 | 预期 |
|---|---|---|
| CLM-01 | 提交理赔报案 | 生成 claim_no |
| CLM-02 | 上传身份证、病历、发票材料 | 材料进入 uploaded 状态 |
| CLM-03 | 案件转补充材料 | 状态变为 supplement |
| CLM-04 | 案件审核通过 | 状态变为 approved |
| CLM-05 | 标记已赔付 | 状态变为 paid |

## 8. API 快速测试

### 登录并获取 Token

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8001/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123","portal":"admin"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

### 查看大屏和余额预警

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8001/api/dashboard
```

### 查看外部服务状态

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8001/api/providers/status
```

### 模拟发送参保名单

```bash
curl -X POST "http://127.0.0.1:8001/api/enrollment/send?enterprise_id=1&plan_id=1&kind=enrollment" \
  -H "Authorization: Bearer $TOKEN"
```

### 模拟支付回调

```bash
curl -X POST http://127.0.0.1:8001/api/payments/callback \
  -H 'Content-Type: application/json' \
  -d '{"order_no":"PAY-ORDER-NO","status":"paid","provider_trade_no":"MOCK-TRADE"}'
```

## 9. 验收前检查清单

- [ ] `python3 -m py_compile backend/app.py` 通过。
- [ ] 浏览器平台端可以登录。
- [ ] 用户端菜单按权限隐藏。
- [ ] `/docs` 可以打开。
- [ ] 所有关键操作有成功和失败提示。
- [ ] 充值回调不会重复入账。
- [ ] 参停保发送有 request_id。
- [ ] 理赔材料列表可以查看。
- [ ] 大屏余额预警阈值在 3–7 天范围内。
- [ ] 生产环境已切换 HTTPS 和真实 Provider。

## 10. 常见问题

### 端口被占用

```bash
lsof -nP -iTCP:8001 -sTCP:LISTEN
```

停止旧进程后重新启动 Uvicorn。

### 用户端显示平台菜单

使用带 `portal=enterprise` 的链接，并增加新的 `v` 参数刷新浏览器缓存。

### 小程序请求失败

检查 `apiBase` 是否为 HTTPS 地址、服务器是否配置小程序合法域名，以及登录 Token 是否有效。

### 真实通知未发出

确认 `INTEGRATION_MODE=real`、供应商 URL 和密钥已配置，并先检查 `/api/providers/status`。
