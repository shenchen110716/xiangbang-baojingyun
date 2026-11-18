# 无忧保开放平台 API 接口使用说明

> ⚠️ **当前状态：设计规范，尚未上线。** 本说明与《完整实现设计方案》配套，描述开放平台正式交付后的对外接口形态，供企业客户技术团队提前评估对接工作量。上线节奏见设计方案第 8 节（P0–P3 四期），实际联调以沙箱环境开通为准。

## 1. 概述

无忧保开放平台面向企业客户（ERP / HR / 用工平台）提供 server-to-server API，覆盖四类能力：

| 能力 | 说明 | 所需 scope |
|---|---|---|
| 参保/停保申报 | 批量提交参保、停保申报单，异步回执 | `declaration:write` |
| 参保人员名单 | 名单查询、保障期间、操作历史、异步导出 | `insured:read` |
| 缴费 | 账户余额、发起充值、收支流水、发票记录 | `account:read` / `recharge:write` |
| 保单 | 保单列表、详情、短时效下载链接 | `policy:read` |

另有 Webhook 事件回调（`webhook:manage`）用于接收申报回执、充值到账等异步通知。

## 2. 环境与接入流程

| 环境 | Base URL | 说明 |
|---|---|---|
| 生产 | `https://bx.xbbzp.com/openapi/v1` | 真实数据 |
| 沙箱 | 同域，使用 `env=sandbox` 密钥 | 独立沙箱企业，预置余额与测试数据，支付走模拟通道 |

接入流程：

1. **申请密钥**：联系平台运营，在管理后台为贵司签发 AppId / AppSecret（Secret 仅创建时明文显示一次，请妥善保存）；按需配置 scopes 与 IP 白名单。
2. **沙箱联调**：使用沙箱密钥完成签名、申报、回调全流程验证。
3. **切换生产**：更换为生产密钥，正式上线。密钥支持双密钥轮换（轮换期新旧密钥并行有效 7 天）。

## 3. 认证与请求签名

所有请求必须携带以下请求头：

| Header | 说明 |
|---|---|
| `X-Api-Key-Id` | 密钥 AppId |
| `X-Timestamp` | Unix 秒级时间戳，与服务器时钟偏移不得超过 ±300 秒 |
| `X-Nonce` | 随机字符串（建议 UUID），同一密钥下不可重复（防重放） |
| `X-Signature` | 按下述算法计算的 HMAC-SHA256 签名（hex 小写） |
| `X-Sign-Version` | 固定 `2` |

签名串拼接（各段以 `\n` 连接）：

```
StringToSign = METHOD \n PATH \n sorted_query \n X-Timestamp \n X-Nonce \n sha256(body)
X-Signature  = HMAC-SHA256(AppSecret, StringToSign)
```

- `METHOD`：大写 HTTP 方法，如 `POST`。
- `PATH`：不含域名与查询串的路径，如 `/openapi/v1/enrollments`。
- `sorted_query`：查询参数按 key 字典序排序后以 `&` 连接（`a=1&b=2`），无查询串则为空字符串。
- `sha256(body)`：请求体原始字节的 SHA-256 hex 小写；GET 等无请求体时对空串求哈希。

Python 签名示例：

```python
import hashlib, hmac, time, uuid

def sign(method: str, path: str, query: dict, body: bytes, app_secret: str):
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    sorted_query = "&".join(f"{k}={v}" for k, v in sorted(query.items()))
    body_hash = hashlib.sha256(body or b"").hexdigest()
    string_to_sign = "\n".join([method.upper(), path, sorted_query, ts, nonce, body_hash])
    signature = hmac.new(app_secret.encode(), string_to_sign.encode(), hashlib.sha256).hexdigest()
    return {"X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": signature, "X-Sign-Version": "2"}
```

## 4. 通用约定

- **统一响应**：

```json
{ "code": 0, "message": "ok", "data": { }, "request_id": "req_…" }
```

- **错误码分段**：`40100` 签名/认证类、`40300` 权限类、`42200` 业务校验、`42900` 限流（响应带 `Retry-After`）、`50000` 系统错误。排错时请记录 `request_id`。
- **分页**：游标式 `?cursor=&limit=`，`limit` 默认 50、上限 200，响应携带 `next_cursor`，为空表示到底。
- **增量同步**：列表接口支持 `updated_since`（ISO8601 时间戳）。
- **幂等**：所有写接口必须携带 `Idempotency-Key` 头（建议 UUID）。同 key 同请求体返回首次结果快照；同 key 不同请求体返回 409。
- **限流**：默认 10 QPS/密钥；批量申报 60 次/小时；异步导出 10 次/小时。

## 5. 参保/停保申报

参保落地后仍需保司与运营确认，接口为**异步申报单模型**：受理即返回申报单号，终态通过查询或 Webhook 获知（回执 SLA：T+1 工作日）。

```
POST /openapi/v1/enrollments        批量参保申报（≤500 人/单）
POST /openapi/v1/terminations       批量停保申报
GET  /openapi/v1/declarations/{id}  申报单状态 + 逐人明细
GET  /openapi/v1/declarations       申报单列表
```

参保申报示例：

```json
POST /openapi/v1/enrollments
Idempotency-Key: 3f2a…

{
  "plan_id": 12,
  "items": [
    {
      "name": "张三",
      "id_type": "idcard",
      "id_number": "110101199001011234",
      "phone": "13800000000",
      "position_id": 7,
      "expect_effective_date": "2026-12-01",
      "external_person_id": "EMP-0012"
    }
  ]
}
```

受理响应（同步校验通过）：

```json
{ "code": 0, "data": { "declaration_id": "DEC202612010001", "status": "accepted", "item_count": 1 } }
```

申报单状态机：`accepted → processing → succeeded / partial_succeeded / failed`（终态逐人携带 `reason`）。

受理时即时拒绝的情形：证件号格式错误、重复在保、**服务费账户余额不足**（整单 422 拒绝，请先充值）。

## 6. 参保人员名单

```
GET  /openapi/v1/insured-persons?status=&position_id=&updated_since=&cursor=
GET  /openapi/v1/insured-persons/{id}            含当前保障期间
GET  /openapi/v1/insured-persons/{id}/history    参停保操作历史
POST /openapi/v1/insured-persons/export          异步导出 XLSX，返回 task_id
GET  /openapi/v1/export-tasks/{id}               完成后返回短时效签名下载链接
```

- 状态语义：`draft` 未参保、`pending` 待生效、`active` 在保、`stopped` 已停保。
- **身份证号默认脱敏**（如 `110***********1234`）；明文需 `insured:read_sensitive` scope，须先签署数据安全承诺由平台人工开通。

## 7. 缴费（预付费余额）

平台采用预付费模型：保费户 `premium_balance` 与服务费户 `usage_balance` 分账管理。

```
GET  /openapi/v1/accounts                两户余额与日服务费
POST /openapi/v1/recharges               发起充值 {account, amount, channel}
GET  /openapi/v1/recharges/{id}          充值单状态
GET  /openapi/v1/ledger-entries?account=&from=&to=&cursor=   收支流水
GET  /openapi/v1/invoices                发票记录
```

- `channel=wechat_native`：返回 `code_url`，企业财务扫码支付，到账后推送 `recharge.succeeded` 事件。
- `channel=bank_transfer`：返回平台收款账户信息；可通过 `POST /recharges/{id}/receipt` 上传付款回单，平台确认到账后推送事件。
- 可设置余额预警阈值，低于阈值推送 `balance.low` 事件（建议配置，避免参保申报因余额不足被拒）。

## 8. 保单

```
GET  /openapi/v1/policies                    本企业相关保单
GET  /openapi/v1/policies/{id}
POST /openapi/v1/policies/{id}/download-url  返回短时效签名下载 URL（300 秒有效）
GET  /openapi/v1/policies/{id}/members       该保单下本企业在保人员
```

下载采用「换取短时效链接」模式，不直接回文件流；链接过期后重新调用即可。

## 9. Webhook 事件回调

```
POST   /openapi/v1/webhooks        注册回调地址（必须 HTTPS）
GET    /openapi/v1/webhooks
DELETE /openapi/v1/webhooks/{id}
POST   /openapi/v1/webhooks/{id}/test   发送测试事件
```

事件字典：

| 事件 | 触发时机 |
|---|---|
| `declaration.completed` | 参保/停保申报单到达终态 |
| `insured.activated` | 人员保障生效 |
| `insured.stopped` | 人员停保生效 |
| `recharge.succeeded` / `recharge.rejected` | 充值到账 / 被驳回 |
| `policy.issued` | 新保单出单 |
| `balance.low` | 账户余额低于预警阈值 |

- 推送请求携带与第 3 节同款 HMAC 签名头（使用贵司 AppSecret），请务必验签。
- payload 只含 ID 与关键字段，敏感明细请回查 API。
- 响应 2xx 即视为投递成功；失败按 1m/5m/30m/2h/12h 指数退避重试 5 次。请按 `event_id` 做幂等消费。

## 10. 常见问题

- **签名总是 40100？** 按序检查：时间戳是否超偏移、PATH 是否含了域名或查询串、query 是否按 key 排序、body 哈希是否用原始字节、Secret 是否复制完整。
- **申报多久出结果？** 回执 SLA 为 T+1 工作日，终态以 `declaration.completed` 事件或申报单查询为准。
- **如何轮换密钥？** 联系平台运营签发新 Secret，轮换期内新旧并行 7 天，切换完成后旧钥失效。
- **调用日志排错**：平台管理后台可按企业/密钥查询每次调用的 `request_id`、状态码与耗时。
