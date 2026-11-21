# 无忧保开放平台 API 接口使用说明

> ✅ **当前状态：P1 只读接口已上线**（参保名单 / 异步导出 / 保单 / 账户余额 / 流水 / 发票，详见第 6–8 节）。**P2 写入接口（参保/停保申报、充值、Webhook）即将上线**，相关章节（第 5、7.2、9 节）当前仅为设计规范，供提前评估对接工作量。机器可读的接口定义见 `GET /openapi/v1/openapi.json`（无需签名）。

## 1. 概述

无忧保开放平台面向企业客户（ERP / HR / 用工平台）提供 server-to-server API，覆盖四类能力：

| 能力 | 说明 | 所需 scope | 状态 |
|---|---|---|---|
| 参保人员名单 | 名单查询、保障期间、操作历史、异步导出 | `insured:read` | ✅ 已上线 |
| 保单 | 保单列表、详情、成员、短时效下载链接 | `policy:read` | ✅ 已上线 |
| 账户查询 | 账户余额、收支流水、发票记录 | `account:read` | ✅ 已上线 |
| 参保/停保申报 | 批量提交参保、停保申报单，异步回执 | `declaration:write` | 🕐 P2 即将上线 |
| 发起充值 | 微信 Native / 银行转账充值 | `recharge:write` | 🕐 P2 即将上线 |

另有 Webhook 事件回调（`webhook:manage`）用于接收申报回执、充值到账等异步通知（🕐 P2 即将上线）。

## 2. 环境与接入流程

| 环境 | Base URL | 说明 |
|---|---|---|
| 生产 | `https://bx.xbbzp.com/openapi/v1` | 真实数据 |
| 沙箱 | 同域，使用 `env=sandbox` 密钥 | 独立沙箱企业，预置余额与测试数据，支付走模拟通道 |

接入流程：

1. **申请密钥**：联系平台运营，在管理后台为贵司签发 AppId / AppSecret（Secret 仅创建时明文显示一次，请妥善保存）；按需配置 scopes 与 IP 白名单。
2. **沙箱联调**：使用沙箱密钥完成签名与查询全流程验证。
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
- `PATH`：不含域名与查询串的路径，如 `/openapi/v1/insured-persons`。
- `sorted_query`：查询参数按 key（key 相同再按 value）字典序排序后，以 `key=value&key=value` 形式连接；**key 与 value 均需 URL 编码**（即标准的 `application/x-www-form-urlencoded` 编码，空格编码为 `+`）；无查询串则为空字符串。服务端等价实现是 Python 的 `urlencode(sorted(parse_qsl(query, keep_blank_values=True)))`——空值参数保留参与签名。
- `sha256(body)`：请求体原始字节的 SHA-256 hex 小写；GET 等无请求体时对空字节串求哈希（`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`）。

> 建议：查询参数值避免使用需要编码的字符；时间戳一律用 `2026-01-01T00:00:00Z` 这类 Z 后缀格式（`+00:00` 中的 `+` 在查询串中会被解析为空格，容易造成签名与解析不一致）。

### 3.1 Python

```python
import hashlib, hmac, time, uuid
from urllib.parse import urlencode

def sign(method: str, path: str, query: dict, body: bytes, app_secret: str) -> dict:
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    sorted_query = urlencode(sorted(query.items()))   # key 字典序 + URL 编码
    body_hash = hashlib.sha256(body or b"").hexdigest()
    string_to_sign = "\n".join([method.upper(), path, sorted_query, ts, nonce, body_hash])
    signature = hmac.new(app_secret.encode(), string_to_sign.encode(), hashlib.sha256).hexdigest()
    return {"X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": signature, "X-Sign-Version": "2"}
```

### 3.2 Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class OpenApiSigner {

    public static Map<String, String> sign(String method, String path,
                                           SortedMap<String, String> query,
                                           byte[] body, String appSecret) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");

        StringBuilder sortedQuery = new StringBuilder();          // key 字典序 + URL 编码
        for (Map.Entry<String, String> e : query.entrySet()) {    // SortedMap 已按 key 排序
            if (sortedQuery.length() > 0) sortedQuery.append('&');
            sortedQuery.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                       .append('=')
                       .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        String bodyHash = hex(sha256.digest(body == null ? new byte[0] : body));

        String stringToSign = String.join("\n",
                method.toUpperCase(), path, sortedQuery.toString(), ts, nonce, bodyHash);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = hex(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Timestamp", ts);
        headers.put("X-Nonce", nonce);
        headers.put("X-Signature", signature);
        headers.put("X-Sign-Version", "2");
        return headers;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

### 3.3 PHP

```php
<?php
function openapi_sign(string $method, string $path, array $query,
                      string $body, string $appSecret): array {
    $ts = (string) time();
    $nonce = bin2hex(random_bytes(16));

    ksort($query);                                    // key 字典序
    $sortedQuery = http_build_query($query);          // URL 编码（RFC1738，空格为 +）

    $bodyHash = hash('sha256', $body);                // 空 body 传 "" 即可
    $stringToSign = implode("\n", [
        strtoupper($method), $path, $sortedQuery, $ts, $nonce, $bodyHash,
    ]);
    $signature = hash_hmac('sha256', $stringToSign, $appSecret);

    return [
        'X-Timestamp'    => $ts,
        'X-Nonce'        => $nonce,
        'X-Signature'    => $signature,
        'X-Sign-Version' => '2',
    ];
}
```

### 3.4 Node.js

```javascript
const crypto = require("crypto");

function openApiSign(method, path, query, body, appSecret) {
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = crypto.randomUUID().replace(/-/g, "");

  const params = new URLSearchParams(
    Object.entries(query).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  );
  const sortedQuery = params.toString(); // key 字典序 + URL 编码

  const bodyHash = crypto.createHash("sha256")
    .update(body || Buffer.alloc(0)).digest("hex");
  const stringToSign = [
    method.toUpperCase(), path, sortedQuery, ts, nonce, bodyHash,
  ].join("\n");
  const signature = crypto.createHmac("sha256", appSecret)
    .update(stringToSign).digest("hex");

  return {
    "X-Timestamp": ts,
    "X-Nonce": nonce,
    "X-Signature": signature,
    "X-Sign-Version": "2",
  };
}
```

## 4. 通用约定

- **统一响应**：

```json
{ "code": 0, "message": "ok", "data": { }, "request_id": "req_…" }
```

- **错误码**：

| code | 典型 HTTP 状态 | 含义 |
|---|---|---|
| `40100` | 401 | 签名/认证类：头不完整、签名错误、时间戳超偏移、nonce 重复、密钥停用或过期 |
| `40300` | 403 | 权限类：缺 scope、IP 不在白名单、下载链接无效或已过期 |
| `42200` | 422 / 404 | 业务校验失败（422）或资源不存在/无权访问（404，统一模糊回答） |
| `42900` | 429 | 限流，响应带 `Retry-After`（秒） |
| `50000` | 500 | 系统错误 |

排错时请记录响应中的 `request_id`。

- **分页**：游标式 `?cursor=&limit=`，`limit` 默认 50、上限 200，响应携带 `next_cursor`，为 `null` 表示到底；下一页请求把 `next_cursor` 原样作为 `cursor` 传入。
- **增量同步**：名单列表支持 `updated_since`（ISO8601 时间戳，建议 `Z` 后缀 UTC）。「更新」按业务事件口径：人员建档、保障期写入/终止、参停保操作任一发生在该时刻之后即命中。该口径**宁滥勿缺**：保障期的生效/终止日期晚于 `updated_since` 的人员会持续命中（例如未来生效的保障期在生效日前每次增量都返回），对接方应按 `id` 做幂等合并，不要假设每人只出现一次。
- **时间口径**：不带时区的时间参数（如流水 `from`/`to`）一律按**北京时间**（UTC+8）理解；带时区偏移或 `Z` 后缀的按其自身时区。响应中的时间戳统一输出带 `+00:00` 偏移量的 UTC。查询串里直接写 `+00:00` 时 `+` 会被解码成空格导致 422，请使用 `Z` 后缀或对 `+` 做 URL 编码（`%2B`）。
- **幂等**（P2 写接口）：所有写接口必须携带 `Idempotency-Key` 头（建议 UUID）。同 key 同请求体返回首次结果快照；同 key 不同请求体返回 409。
- **限流**：默认 10 QPS/密钥；异步导出 10 次/小时/密钥；批量申报 60 次/小时（P2）。
- **接口定义**：`GET /openapi/v1/openapi.json` 返回开放平台独立的 OpenAPI 3.x schema（无需签名），可直接导入代码生成器；平台内部 `/docs` 不含开放平台接口。

## 5. 参保/停保申报（🕐 P2 即将上线）

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

## 6. 参保人员名单（✅ 已上线）

```
GET  /openapi/v1/insured-persons?status=&position_id=&updated_since=&cursor=&limit=
GET  /openapi/v1/insured-persons/{id}            含当前保障期间
GET  /openapi/v1/insured-persons/{id}/history    参停保操作历史
POST /openapi/v1/insured-persons/export          异步导出 XLSX，返回 task_id
GET  /openapi/v1/export-tasks/{id}               完成后返回短时效签名下载链接
```

- 状态语义（内部码与对外语义同时返回）：`draft` 未参保、`pending` 待生效、`active` 在保、`stopped` 已停保。
- **身份证号默认脱敏**（前 3 位 + `*` 填充 + 后 4 位，如 `110***********1234`）；明文需 `insured:read_sensitive` scope，须先签署数据安全承诺由平台人工开通。每次明文访问平台均留审计记录。

列表项示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1024,
        "name": "张三",
        "phone": "13800000000",
        "id_number": "110***********1234",
        "status": "active",
        "status_label": "在保",
        "position_id": 7,
        "occupation": "搬运工",
        "occupation_class": "3类",
        "created_at": "2026-01-05T10:00:00"
      }
    ],
    "next_cursor": null
  },
  "request_id": "req_…"
}
```

详情额外携带 `coverage`（当前保障期间，无保障期则为 `null`）：

```json
"coverage": {
  "policy_id": 3,
  "policy_no": "POL-A-001",
  "effective_at": "2026-01-06T00:00:00",
  "terminated_at": null,
  "status": "active"
}
```

`history` 返回参停保操作时间线（倒序）：`operation_type`（enrollment/termination）、`submitted_at`、`expected_at`、`insurer_confirmed_at`。

### 6.1 异步导出

1. `POST /openapi/v1/insured-persons/export`，请求体可选，支持与列表同款过滤：`{"status": "active", "position_id": 7, "updated_since": "2026-01-01T00:00:00Z"}`。响应：`{"task_id": 15, "status": "pending"}`。
2. 轮询 `GET /openapi/v1/export-tasks/{task_id}`（建议间隔 ≥30 秒；任务由后台循环处理）。状态机：`pending → processing → done / failed / expired`（`failed`/`expired` 时 `error` 字段带原因）。卡在 `processing` 超过 15 分钟的任务由平台自动回收：先重跑一次，再超时则置 `failed`。
3. `status=done` 时响应携带 `download_url`（相对路径，带签名 token）与 `download_expires_at`（Unix 秒）。**链接 300 秒内有效**，过期后重新调用本接口换取新链接即可。
4. 下载链接**无需签名头**，直接 GET 即得 XLSX 文件。文件内证件号脱敏规则与 API 响应一致（由发起导出那一刻密钥是否具 `insured:read_sensitive` 决定）。明文口径的任务，后续查询/换链接时要求当前密钥**仍**持有 `insured:read_sensitive`，否则 403——密钥被降权后旧明文文件立即不可再取。
5. 导出文件在平台**保留 48 小时**（自任务完成起），到期自动删除、任务置 `expired`，请在有效期内取回；过期后重新发起导出即可。

```json
{
  "code": 0,
  "data": {
    "task_id": 15,
    "task_type": "insured_persons",
    "status": "done",
    "created_at": "2026-01-05T10:00:00+00:00",
    "finished_at": "2026-01-05T10:00:30+00:00",
    "error": null,
    "download_url": "/openapi/v1/export-files/15?token=…&expires=1767608760",
    "download_expires_at": 1767608760
  }
}
```

- 导出独立限流 **10 次/小时/密钥**，超限 429 + `Retry-After` + `code=42900`。

## 7. 账户与缴费（预付费余额）

平台采用预付费模型，分两类账户：

- **保费账户（premium）**：按**收款账户分账**。一个收款账户可服务多家保司（`insurers` 列表），同企业可能有多个保费账户，余额按账户分别管理——对账时请以 `account_id` 维度核对，不要把多个账户余额简单相加当作「一个保费户」。
- **服务费账户（usage）**：平台使用费。可用余额是**计算值**：`available = recharged（充值累计） − consumed（全时段计费人天 × 当前日费率 daily_rate）`，不是一个独立存储的余额数字。

### 7.1 账户查询（✅ 已上线）

```
GET  /openapi/v1/accounts                                    分账户保费余额 + 服务费视图
GET  /openapi/v1/ledger-entries?account=&from=&to=&cursor=   收支流水（account=premium|usage）
GET  /openapi/v1/invoices                                    发票记录
```

`GET /openapi/v1/accounts` 响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "premium_accounts": [
      {
        "account_id": 2,
        "label": "平安收款账户",
        "insurers": ["平安"],
        "balance": 500.0
      }
    ],
    "usage": {
      "recharged": 1000.0,
      "consumed": 36.5,
      "available": 963.5,
      "active_people": 12,
      "daily_rate": 0.1
    }
  },
  "request_id": "req_…"
}
```

流水项字段：`account`（premium/usage）、`account_id`（保费流水所属收款账户）、`direction`（credit/debit）、`amount`、`business_type`（recharge/premium_deduction/…）、`business_id`、`occurred_at`。`from`/`to` 为 ISO8601 日期或时间戳：**不带时区按北京时间（UTC+8）理解**，带时区偏移或 `Z` 后缀按其自身时区，平台折算成统一口径后过滤。响应中的 `occurred_at` 输出带 `+00:00` 偏移量的 **UTC 时间**（例如北京时间 1 月 5 日 04:00 的流水，`occurred_at` 为 `2026-01-04T20:00:00+00:00`），对接方展示时请自行转换时区。

发票项字段：`account`、`invoice_type`、`amount`、`title`、`status`、`has_document`、`created_at`。

### 7.2 发起充值（🕐 P2 即将上线）

```
POST /openapi/v1/recharges               发起充值 {account, amount, channel}
GET  /openapi/v1/recharges/{id}          充值单状态
```

- `channel=wechat_native`：返回 `code_url`，企业财务扫码支付，到账后推送 `recharge.succeeded` 事件。
- `channel=bank_transfer`：返回平台收款账户信息；可通过 `POST /recharges/{id}/receipt` 上传付款回单，平台确认到账后推送事件。
- 可设置余额预警阈值，低于阈值推送 `balance.low` 事件（建议配置，避免参保申报因余额不足被拒）。

## 8. 保单（✅ 已上线）

```
GET  /openapi/v1/policies?status=&cursor=&limit=     本企业相关保单
GET  /openapi/v1/policies/{id}
POST /openapi/v1/policies/{id}/download-url          返回短时效签名下载 URL（300 秒有效）
GET  /openapi/v1/policies/{id}/members               该保单下本企业在保人员
```

保单字段：`policy_no`、`plan_id`、`status`、`start_date`、`end_date`、`has_document`、`document_name`、`created_at`。

- 下载采用「换取短时效链接」模式，不直接回文件流：`download-url` 响应携带 `download_url`（带签名 token 的相对路径，无需签名头）与 `expires_at`（Unix 秒）；**链接 300 秒内有效**，过期后重新调用即可。保单暂无电子保单文件时（`has_document=false`）返回 `code=42200`。
- `members` 返回该保单下本企业人员的 `person_id`、`name`、`id_number`（脱敏规则与第 6 节一致）、`status`、`effective_at`、`terminated_at`、`endorsement_no`。

## 9. Webhook 事件回调（🕐 P2 即将上线）

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

- **签名总是 40100？** 按序检查：时间戳是否超偏移、PATH 是否含了域名或查询串、query 是否按 key 排序并 URL 编码、body 哈希是否用原始字节、Secret 是否复制完整、`X-Sign-Version` 是否为 `2`。
- **Postman 里怎么调试？** 导入 `open-api-postman-collection.json`（随本文档一同提供），填好 `baseUrl` / `keyId` / `appSecret` 三个集合变量即可——签名由集合级 pre-request 脚本自动计算。
- **导出任务多久完成？** 后台循环每 30 秒左右处理一批，通常 1 分钟内完成；完成后下载链接每次换取有效 300 秒，过期重新查询任务即可。
- **如何轮换密钥？** 联系平台运营签发新 Secret，轮换期内新旧并行 7 天，切换完成后旧钥失效。
- **调用日志排错**：平台管理后台可按企业/密钥查询每次调用的 `request_id`、状态码与耗时。
