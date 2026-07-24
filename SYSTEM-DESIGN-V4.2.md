# 响帮帮无忧保 v4.2 完整产品与技术设计

文档状态：设计基线
版本：v4.2
更新日期：2026-07-16
适用范围：平台电脑端、企业电脑端、业务员电脑端、微信小程序、FastAPI 后端、Java 镜像及外部用工数据集成

## 1. 文档定位

本文档是 v4.2 的独立、完整设计基线，不需要与 v4.1 拼接阅读。v4.2 延续模块化单体、租户默认拒绝、服务端决定业务规则、不可变账本和版本化迁移原则，并新增或固化以下能力：

1. 平台管理员、业务员、企业主管和项目负责人分权。
2. 项目负责人与实际工作单位的多对多授权及历史责任归属。
3. 业务员查看全部在售保险产品、平台最低售价和本人佣金财务闭环。
4. 以实际用工企业反馈的真实入职、离职时间作为参停保考核的唯一基准。
5. 真实用工事实的批量导入、外部推送、匹配、纠错和审计。
6. 参保、停保及时率、反馈及时率、成本风险和责任原因的版本化计算。

当存量实现与本文档冲突时，新增开发以本文档为准，存量数据和接口通过明确迁移计划收敛。本文档不授权生产发布、生产迁移、密钥变更或小程序提审。

## 2. 目标与非目标

### 2.1 建设目标

- 企业主管可以维护项目负责人及其实际工作单位范围。
- 项目负责人只能操作被授权单位的数据，无授权即无数据。
- 业务员可以查看全部在售产品和平台最低售价，但只能查看本人佣金、结算和收款。
- 真实入离职事实与员工主数据、保障事实和操作事实相互独立并可追溯。
- 同一员工多次入职、离职或重新入职形成多段独立用工事实。
- 主及时率始终按真实入离职时间与真实保障时间计算。
- 反馈迟报、操作迟延、系统迟延和保司迟延可以区分责任。
- 导入、重算、查询和导出遵循同一租户与实际工作单位过滤规则。
- Python/Alembic 是数据库迁移唯一权威，Java 模型和 Mapper 同步跟随。

### 2.2 非目标

- 不拆分微服务，不引入通用工作流或低代码平台。
- 不允许前端计算期望停保时间、佣金、权限范围或考核结果。
- 不以员工创建时间、导入时间、当前状态或操作时间代替真实入离职时间。
- 不因反馈宽限期修改保障结果及时率。
- 不允许业务员查看内部成本、平台利润、总返佣、其他业务员佣金或企业销售数据。
- 本阶段不自动向真实外部账户付款，不自动发布生产版本。

## 3. 总体架构与领域边界

系统继续采用 FastAPI 模块化单体：

```text
平台 SPA ───────┐
企业 SPA ───────┼── HTTPS ── API / 模块化单体 ── PostgreSQL
业务员 SPA ─────┤                     │
微信小程序 ─────┘                     ├── Worker / Outbox
                                      ├── 私有对象存储
                                      ├── Redis（限流与任务协调）
                                      └── 保司、支付及用工企业接口
```

领域边界：

| 模块 | 权威职责 | 不负责 |
| --- | --- | --- |
| Identity & Scope | 用户、角色、租户、项目授权、会话 | 计算及时率 |
| Enterprise | 企业、实际工作单位、岗位 | 保障生效事实 |
| Product & Pricing | 产品、费率、最低售价、规则版本 | 修改历史快照 |
| Employment Facts | 反馈批次、用工期间、匹配、纠错 | 推断保障状态 |
| Coverage Operations | 参停保申请、批次、操作者、单位快照 | 决定真实入离职时间 |
| Policy | 保单成员及实际保障期间 | 保存员工用工期间 |
| Timeliness | 规则归一化、事实比对、归责和统计 | 修改上游事实 |
| Agent & Commission | 业务员门户、佣金、结算、付款分配 | 暴露平台内部成本 |
| Billing & Ledger | 账户、充值、资金流水、对账 | 直接修改佣金结果 |
| Audit & Reporting | 审计、查询、导出、数据质量 | 绕过领域权限 |
| Integration | 签名、幂等、Outbox、重试、外部回执 | 直接决定领域状态 |

权威事实分层：

```text
真实用工事实  EmploymentFact
保障事实      PolicyMember.effective_at / terminated_at
操作事实      EnrollmentRequest / TerminationRequest 及提交人快照
产品规则      ProductRuleVersion
计算结果      EmploymentTimelinessResult
```

缺少真实用工事实时，不得从其他数据反推正式考核结果；记录进入待匹配或数据质量指标。

## 4. 身份、角色与数据范围

### 4.1 业务角色

| 角色 | 数据范围 | 核心能力 |
| --- | --- | --- |
| 平台管理员 | 全平台，显式跨企业 | 产品、企业、业务员、佣金、支付、审核、跨企业统计 |
| 业务员 | 全部在售产品＋本人财务数据 | 产品最低售价、本人佣金、结算单、付款记录 |
| 企业主管 | 本企业全部数据 | 人员、项目授权、参停保、理赔、用工事实、绩效 |
| 项目负责人 | 被授权实际工作单位 | 员工、岗位、参停保、批量操作、理赔、项目看板 |

企业角色采用：

```text
enterprise_role = owner | project_manager
```

### 4.2 UserEmployerScope

```text
UserEmployerScope
- id
- user_id
- enterprise_id
- actual_employer_id
- responsibility_type: primary | collaborator
- granted_by
- assigned_at
- revoked_at
- status: active | revoked
- created_at / updated_at
```

约束：

- 一个实际工作单位可以有多个负责人。
- 同一实际工作单位在同一时刻必须且只能有一名有效主要负责人。
- 主要负责人变更通过关闭旧授权和创建新授权保留历史。
- `enterprise_id`、`actual_employer_id` 和用户租户必须一致。
- 项目负责人接口必须附加有效授权过滤；不得回退为全企业权限。
- 漏操作按业务事件发生时的主要负责人归责，不按查询时的当前负责人归责。

### 4.3 请求鉴权

```text
验证 Token 与 session_version
→ 解析角色和 enterprise_id
→ 校验 permission
→ 解析有效 actual_employer_id 范围
→ Repository 强制附加企业与单位过滤
→ 校验目标对象仍在同一范围
→ 敏感操作写审计
```

平台跨企业操作必须具备权限并显式指定企业。业务员本人接口从 Token 解析 `agent_id`，不接受 Query、Path 或 Body 传入代理身份。

## 5. 业务员产品与佣金门户

### 5.1 产品中心

业务员可以查看平台全部在售保险产品，即使产品尚未为该业务员配置佣金关系。允许返回：

- 保险公司、产品名称、保障责任、职业类别。
- 计费模式、生效模式、产品状态。
- 平台销售最低价。
- 本人佣金配置状态；没有关系时显示“未配置”。

平台最低售价由后端价格服务计算，前端只展示。禁止返回：

- 保险原价、保司结算底价。
- 平台利润、总返佣金额。
- 其他业务员佣金。
- 企业实际销售、投保和经营数据。

产品读取接口和本人佣金接口分离，响应 Schema 使用字段白名单，不能仅在前端隐藏内部字段。

### 5.2 本人佣金指标

业务员门户展示：

- 预估累计佣金。
- 待结算佣金。
- 待支付佣金。
- 已支付佣金。

明细支持按时间、企业、保司和产品查询及导出。查询、汇总和导出共用同一过滤服务。

### 5.3 佣金财务闭环

```text
AgentCommissionStatement
AgentCommissionStatementItem
AgentCommissionPayment
AgentCommissionPaymentAllocation
```

- 结算单固化业务员、结算期间、币种、总额和状态。
- 结算项固化佣金来源、保单成员、产品、企业、计算期间和金额快照。
- 付款记录平台实际付款事实、渠道、流水号、付款时间和凭证。
- 分配表支持一张结算单分次付款及一次付款覆盖多张结算单。
- 已确认结算项不得原地改写，差错通过调整项或冲正记录处理。
- 分配金额不得超过付款可分配余额或结算单未付余额。

## 6. 真实用工事实模型

### 6.1 EmploymentFeedbackBatch

```text
EmploymentFeedbackBatch
- id
- enterprise_id
- actual_employer_id（可空，批次可包含多个本企业单位）
- source_type: manual_import | api | system_sync
- source_filename / source_file_hash
- reported_at
- imported_by / imported_at
- total_rows / valid_rows / invalid_rows
- status: uploaded | previewed | confirmed | imported_pending_calculation |
          completed | rejected | failed
- preview_version / confirm_token_digest
- created_at / updated_at
```

文件保存到私有对象存储；数据库仅保存对象键、文件哈希和审计信息。相同企业、来源和文件哈希不得重复确认。

### 6.2 EmploymentFact

一条记录表示一个员工的一段真实用工期间：

```text
EmploymentFact
- id
- enterprise_id
- actual_employer_id
- person_id（匹配完成前可空）
- external_employee_no
- external_employment_id
- actual_hire_at
- actual_leave_at
- feedback_reported_at
- batch_id / source_event_id
- revision_no / previous_version_id
- status: active | superseded | pending_match | conflict | voided
- created_by / created_at
```

约束：

- `actual_leave_at` 为空表示仍在职，否则必须晚于 `actual_hire_at`。
- 同一人员可以有多段不重叠的有效用工事实。
- 纠错创建新版本并将旧版本标记为 `superseded`，不得覆盖旧值。
- `source_event_id` 在数据源身份范围内唯一，保证外部推送幂等。
- 所有时间点按企业业务时区解析后以 UTC 保存，响应时带时区语义。

### 6.3 EmploymentFactMatch

```text
EmploymentFactMatch
- id
- employment_fact_id
- match_status: matched | pending | ambiguous | rejected
- match_method: external_employment_id | identity_hire | employee_no | manual
- candidate_person_id / matched_person_id
- confidence / reason
- confirmed_by / confirmed_at
- created_at
```

将匹配工作流与真实用工事实分离，避免人工候选信息污染权威事实。

### 6.4 身份匹配与隐私

匹配优先级：

1. 数据源范围内的 `external_employment_id`。
2. 本企业＋实际工作单位＋身份证号哈希＋真实入职时间。
3. 本企业和实际工作单位范围内的外部员工编号。
4. 人工匹配。

身份证原文仅在受控导入事务内使用；持久化保存密文和确定性哈希。列表、日志和错误文件只显示脱敏值。原始上传文件必须私有、加密并设置保留期限。

## 7. 用工事实导入与外部集成

### 7.1 标准模板

XLSX/CSV 字段：实际工作单位、外部员工编号、姓名、身份证号、真实入职时间、真实离职时间、反馈时间、外部用工记录号和备注。实际工作单位、姓名、身份证号和入职时间必填；离职时间可空。

### 7.2 两阶段导入

```text
上传
→ 文件与模板校验
→ 按企业业务时区解析
→ 单位范围校验
→ 身份匹配
→ 重复、重叠与版本冲突检查
→ 返回完整预览和错误行
→ 企业主管确认
→ 单事务写入整批事实
→ Outbox 触发及时率重算
```

- 预览不写正式事实。
- 所有阻断错误必须在确认前解决，禁止部分确认。
- 确认令牌绑定企业、上传人、文件哈希和预览版本，并且只能使用一次。
- 事实写入成功但计算暂时失败时，批次进入 `imported_pending_calculation`；不得回滚或重复导入事实。
- 大批量计算由 Worker 重试，单条修正可以同步计算。

### 7.3 外部接口

```text
POST /integrations/employment-events
POST /integrations/employment-events/batch
GET  /integrations/employment-events/{source_event_id}
```

要求 API Key 或签名认证、时间戳、nonce 防重放、`source_event_id` 幂等、单条与批量模式、行级错误、限流和完整审计。认证身份固定绑定企业及允许的实际工作单位，Body 不能扩大范围。

## 8. 保障事实与规则版本

`PolicyMember` 是真实保障期间的权威来源：

```text
PolicyMember.effective_at
PolicyMember.terminated_at
```

参停保申请必须保存：实际提交人、企业、实际工作单位、产品和规则版本快照、提交时间、期望生效/停保时间及最终保司确认时间。即使人员或负责人之后调岗，历史操作归属也不能改变。

产品规则使用版本化模型或等价快照，至少包含：

- `billing_mode`: monthly | daily。
- `effective_mode`: next_day | immediate。
- 离职日期是否表示最后工作日。
- 最短保障周期。
- 项目或保单终止边界。
- 业务时区。
- 反馈宽限规则。

统一规则服务提供：

```text
normalizeEnrollment(actual_hire_at, product_rule)
normalizeTermination(actual_leave_at, product_rule)
feedbackDeadline(event_type, actual_business_at, product_rule)
```

前端、报表和 Java 镜像不得各自复制日期算法。

## 9. 参保及时率

定义：

```text
H = 真实入职时间
E = 实际保障生效时间
```

判定顺序：

1. `H` 在未来：`pending`，不进入分母。
2. `H` 时刻存在连续有效保障，且 `E = H`：`timely`。
3. `H` 时刻存在连续有效保障，且 `E < H`：`early`，计入及时并计算提前时长和额外保费。
4. 首次有效保障 `E > H`：`late`，计算延误时长和保障缺口。
5. 已到 `H` 且没有任何有效保障：`missing`。
6. 多个保障候选无法确定产品或期间：`conflict`，不进入正式指标。

曾提前生效但在 `H` 前已经终止的保障，不构成 `H` 时刻连续有效保障。

```text
参保及时率 = (timely + early) / (timely + early + late + missing) × 100%
```

提前参保属于及时，但其提前天数和额外保费必须单独展示。

## 10. 停保及时率

定义：

```text
L = 真实离职时间
S = 规则服务计算的期望停保时间
T = 实际保障终止时间
```

规则：

- 月保单且离职日期表示最后工作日：`S` 为离职业务日次日 00:00。
- 按天或即时产品：`S = L`。
- 最短保障周期、项目结束日等限制由 `normalizeTermination` 统一处理。

判定顺序：

1. 没有离职事实或尚未到 `S`：`pending`，不进入分母。
2. `T = S`：`timely`。
3. `T < S`：`premature`，计算保障缺口，不计及时。
4. `T > S`：`late`，计算额外计费期间和保费损失。
5. 已超过 `S` 仍无终止记录：`missing`。
6. 无法确定唯一保障或规则：`conflict`，不进入正式指标。

```text
停保及时率 = timely / (timely + premature + late + missing) × 100%
```

## 11. 综合指标、反馈宽限与责任

### 11.1 综合及时率

入职和离职各算一个业务事件，不按去重员工人数计算：

```text
综合及时率 = 及时参保事件数 + 及时停保事件数
             ----------------------------------- × 100%
             已验证应参保事件数 + 已验证应停保事件数
```

及时参保事件包含 `timely` 和 `early`；及时停保事件只包含 `timely`。

### 11.2 反馈及时率

- 月保单的入职和离职反馈均允许真实事件发生后 24 小时内上报。
- 按天或即时产品必须不晚于真实事件时间反馈，不设宽限。
- 宽限期只影响反馈及时率和责任解释，不改变参保、停保或综合及时率。
- 可信的 `feedback_reported_at` 为权威反馈时间。
- 缺少可信反馈时间时使用 `imported_at`，同时标记 `reported_time_fallback`，并纳入数据质量指标。

```text
反馈及时 = feedback_reported_at <= feedbackDeadline(...)
反馈及时率 = 及时反馈事件数 / 已验证反馈事件数 × 100%
```

### 11.3 责任原因

```text
source_feedback_late
operator_processing_late
system_processing_late
insurer_confirmation_late
unassigned_responsibility
normal
```

责任判定使用版本化规则，并保存判定证据时间：反馈时间、操作提交时间、系统发送时间和保司确认时间。单一主原因用于汇总，完整原因链保存在证据字段中。

- 成功参停保操作归实际提交人。
- 批量操作每行归上传并确认该业务批次的人。
- 漏参保、漏停保归事件发生时该单位的主要负责人。
- 当时没有主要负责人时为 `unassigned_responsibility`，不得归给当前管理员。
- 月保单在 24 小时宽限内反馈不判定为用工企业迟报，但保障主结果仍按真实事件时间计算。

## 12. 计算结果与重算

```text
EmploymentTimelinessResult
- id
- employment_fact_id / employment_fact_revision_no
- operation_type: enrollment | termination
- enterprise_id / actual_employer_id / person_id
- responsible_user_id / primary_manager_user_id
- actual_business_at
- expected_coverage_at
- actual_coverage_at
- timeliness_status: timely | early | late | missing | premature |
                     pending | unmatched | conflict
- delay_seconds / early_seconds
- coverage_gap_seconds
- excess_premium / early_premium
- feedback_status / feedback_deadline_at
- responsibility_reason / responsibility_evidence_json
- product_rule_version / calculation_version
- calculated_at
- status: current | superseded
```

用工事实、保障事实、操作事实或产品规则变化时，旧结果标记为 `superseded` 并生成新结果。重算幂等键至少包含事实版本、操作类型、规则版本和计算版本。

批量重算采用 Outbox 和 Worker。任务可重试、可观察，不得重复生成多个当前结果。待匹配、冲突和规则缺失记录进入数据质量队列，不进入正式及时率。

## 13. 页面与报表

### 13.1 企业主管

- 项目负责人授权与主要负责人变更。
- 用工事实模板下载、上传预览、确认和批次详情。
- 待人工匹配、冲突处理和事实纠错。
- 及时率总览、责任分析、成本风险、明细和导出。

筛选条件包括操作员、实际工作单位、真实业务时间段、参保/停保、手工/批量、及时状态和责任原因。

### 13.2 项目负责人

- 项目看板、参保员工、参停保操作、批量导入、岗位、理赔和账户设置。
- 所有页面与接口只显示有效授权范围。
- 不提供全企业用工事实批次确认或授权关系维护能力。

### 13.3 业务员

- 全部在售产品及平台最低售价。
- 我的佣金、佣金结算单、平台付款记录和账户设置。
- 无权访问企业参停保明细和其他业务员数据。

### 13.4 平台管理员

- 跨企业统计、数据质量、规则异常和重算任务。
- 佣金结算、付款和分配管理。
- 跨企业导出必须记录筛选条件、导出人、时间、行数和文件摘要。

统计卡片至少包括应参保、及时参保、延迟参保、漏参保、应停保、及时停保、提前停保、延迟停保、漏停保、综合及时率、反馈及时率、操作员可归责及时率、保障缺口及额外保费。

## 14. API 契约

### 14.1 项目授权

```text
GET    /employer-scopes
POST   /employer-scopes
PATCH  /employer-scopes/{id}
DELETE /employer-scopes/{id}
POST   /actual-employers/{id}/primary-manager
```

### 14.2 用工事实

```text
GET  /employment-feedback/template
POST /employment-feedback/import/preview
POST /employment-feedback/import/confirm
GET  /employment-feedback/batches
GET  /employment-feedback/batches/{id}
GET  /employment-facts
PATCH /employment-facts/{id}/correct
GET  /employment-facts/unmatched
POST /employment-facts/unmatched/{id}/match
```

### 14.3 及时率

```text
GET  /timeliness/summary
GET  /timeliness/details
GET  /timeliness/export
POST /timeliness/recalculate
GET  /timeliness/data-quality
```

### 14.4 业务员门户

```text
GET /agent-portal/products
GET /agent-portal/commissions/summary
GET /agent-portal/commissions/details
GET /agent-portal/commissions/export
GET /agent-portal/statements
GET /agent-portal/statements/{id}
GET /agent-portal/payments
```

列表与导出必须调用同一查询服务。分页、排序、时区和枚举响应保持一致。敏感字段使用专用响应 Schema 白名单。

## 15. 安全、审计与错误处理

- 企业与实际工作单位采用默认拒绝的数据范围策略。
- 上传限制类型、文件大小、行数和压缩炸弹风险；解析在隔离环境完成。
- 身份证、付款凭证和原始导入文件私有存储并加密。
- 日志不得记录身份证原文、Token、API Key、签名密钥或完整付款账号。
- 登录、授权变更、事实修正、人工匹配、重算、结算、付款、导出均写审计。
- 外部请求使用签名、时间戳、nonce、限流和幂等键。
- 业务错误返回稳定错误码；行级导入错误可下载但必须脱敏。
- 事实导入成功、计算失败时保留事实并重试计算；不得用回滚事实掩盖计算故障。
- 产品规则缺失、候选保障冲突和人员未匹配不得静默猜测。

## 16. 数据库迁移与兼容

实施时必须从 `main` 最新已合并迁移头开始，单一任务持有迁移锁。推荐迁移顺序：

1. 角色字段与 `UserEmployerScope`。
2. `EmploymentFeedbackBatch`、`EmploymentFact`、`EmploymentFactMatch`。
3. 参停保操作快照与产品规则版本字段。
4. `EmploymentTimelinessResult` 及必要索引。
5. 佣金结算、付款和分配表。

迁移要求：

- Python/Alembic 是唯一结构迁移来源。
- Java 实体、Mapper 和兼容读取跟随 Alembic 结构，不建立独立迁移历史。
- 验证空库初始化、旧库升级和回滚/前滚修正方案。
- 存量数据不得自动伪造真实入离职时间；标记为待补充事实。
- 新旧接口并存期间明确弃用窗口，不以字段同名掩盖语义变化。
- 主要负责人唯一性应由事务校验和数据库可表达的约束共同保障。

## 17. 验收场景

### 17.1 权限

- 项目负责人只能看到授权实际工作单位的数据。
- 撤销授权后新请求立即失去访问范围，历史责任仍可追溯。
- 企业主管可以查看本企业全部单位，但不能跨企业。
- 业务员产品接口返回全部在售产品，却不泄露内部成本和他人佣金。
- 业务员不能通过传入 `agent_id` 查询他人数据。

### 17.2 用工事实

- 同一员工二次入职形成两段独立事实。
- 修正真实时间创建新版本且旧版本可审计。
- 重复文件、重复确认和重复 `source_event_id` 不产生重复事实。
- 冲突和未匹配记录不进入正式指标。
- 导入全部阻断错误处理完毕后才能原子确认。

### 17.3 及时率

- 真实入职早于保障生效，判定延迟参保。
- 到达真实入职时间仍无保障，判定漏参保。
- 提前生效且入职时连续有效，计入及时并计算额外保费。
- 提前生效但入职前已终止，不计及时。
- 月保单最后工作日次日 00:00 停保，判定及时。
- 离职前停保判定提前停保和保障缺口。
- 离职后停保判定延迟并计算额外保费。
- 月保单入职、离职反馈均验证 24 小时边界。
- 按天或即时产品验证零宽限边界。
- 宽限期不改变保障主指标。
- 漏操作归事件发生时的主要负责人；无人负责时保持未分配。

### 17.4 佣金与文件

- 结算单可分次付款，一次付款可分配多张结算单。
- 分配金额不超过付款余额和结算单未付余额。
- XLSX 模板与导出文件可以实际打开。
- 页面查询和导出使用相同筛选与数据范围。

## 18. 测试与交付门槛

进入可合并状态前至少完成：

- Python 单元、系统、安全、幂等和权限反向测试。
- Java Maven 测试及 Python/Java 字段语义一致性检查。
- Web 正式构建和关键页面权限测试。
- 小程序语法、编译、预览及授权范围测试。
- 空库初始化、旧库升级和迁移失败恢复验证。
- 导入模板、错误文件和导出文件打开验证。
- 月保单 24 小时与按天/即时零宽限边界测试。
- 跨企业、跨单位、跨业务员的数据泄漏测试。
- 大批量导入、重算幂等、失败重试和可观察性测试。

## 19. 实施顺序与协作边界

推荐按以下阶段串行推进：

1. 项目负责人多对多授权及主要负责人历史。
2. 用工事实、反馈批次、匹配和版本纠错。
3. 导入预览、原子确认与外部接口认证骨架。
4. 参停保操作快照、产品规则服务和及时率引擎。
5. 企业主管绩效查询、数据质量与导出。
6. 业务员产品、本人佣金、结算与付款闭环。
7. Web、小程序、Java 镜像和全量权限回归。

公共用户、权限、迁移、佣金、看板、公共类型和路由必须串行修改。开始实现前应核对并复用现有 `salesperson-portal` 活动分支成果，不能建立第二套业务员接口或导航。

## 20. 最终业务口径

1. 真实入离职时间是参停保保障结果考核的唯一基准。
2. 月保单的入职和离职反馈均允许 24 小时宽限；按天或即时产品不设宽限。
3. 反馈宽限只解释反馈责任，不修改保障主及时率。
4. 提前参保计入及时但单列成本；提前停保不计及时并单列保障缺口。
5. 入职和离职分别按业务事件计数，不以员工人数代替事件数。
6. 无真实用工事实、未匹配或冲突记录不进入正式指标。
7. 项目负责人无授权即无数据；漏操作按事件发生时的主要负责人归责。
8. 业务员可见全部在售产品及平台最低售价，只能查看本人佣金和收款。
9. 所有历史事实、规则和计算结果均可审计，不以覆盖更新破坏历史。
