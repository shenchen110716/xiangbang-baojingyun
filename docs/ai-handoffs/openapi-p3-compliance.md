# openapi-p3-compliance（story-p3-compliance 合规硬化）

- **状态**：implementation complete + **review-p3 返工（attempt 2）完成**，全部测试门槛绿（见下），**未 commit**（由 orchestrator 走 commit 流程）
- **分支**：feature/openapi-platform（基于 P2=58141f3）
- **代理**：Claude（orchestrate run r-20261119-openapi-platform step 9）
- **权威验收**：`../../docs/stories/story-p3-compliance.md`（blueprint 根 docs/stories/）10 条 AC

## 范围与实现

### AC1 迁移 + 回填
- `backend/migrations_alembic/versions/e5f8a1c3d7b2_openapi_p3_compliance.py`：revision `e5f8a1c3d7b2`，down_revision `d8e3f6a2b9c4`（P2 head），单 head。加 `id_number_hash String(64)`（NOT NULL server_default ''，索引 `ix_insured_people_id_number_hash`）+ `id_number_cipher Text` 可空；同迁移内调用共享回填。
- 回填函数 `backend/core/migrations.py:5` `backfill_insured_id_numbers(connection, batch_size=500)`：分批（避免大表长事务）、幂等可重跑（只选 hash/cipher 为空且明文非空的行）。
- SQLite 运行时桥同步：`backend/core/migrations.py:99-102`（加列 + 索引 + 回填）。

### AC2 双写
- `backend/models/insured.py:51-66`：SQLAlchemy `before_insert`/`before_update` 事件 `_sync_id_number_crypto`，对**所有** ORM 写路径（管理端新建/修改、导入、openapi 申报落库、小程序）透明生效，同事务三列一致。

### AC3 读切换
- 等值/查重全部走 hash：`routers/insured.py:37,505`、`services/openapi/declarations.py:97,105,197,293,301`、`services/employment_matching.py:63`、`services/timeliness_reporting.py:99`。
- 展示/导出走 cipher 解密：`models/insured.py:37` `decrypted_id_number()`（**密文优先，解密失败回退明文列**——AC10 回滚通道）；调用点见 insured.py:79、serializers.py:141、policies.py:140、declarations.py:259 等。
- `services/serialization.py:3` 全局排除 hash/cipher 两列，防止任何 serialize() 出口泄漏。
- 静态守卫：`tests/openapi_compliance_smoke.py` 的 `test_no_plaintext_equality_queries` 用正则扫 backend/ 禁止新增 `InsuredPerson.id_number ==` / `.like` 查询。

### AC4 出口默认脱敏
- insured 导出 `routers/insured.py:167`；enrollment 保司 payload/邮件/XLSX `routers/enrollment.py:53,78,96,120`；保单导出 `routers/reports.py:306` + 保费明细 `reports.py:138`；openapi P1 链路沿用 `mask_id_number_openapi`。另把 insurer_settlement.py:151、claims.py:62、timeliness_reporting.py:208 一并脱敏。
- 既有测试相应更新（AC4 条款允许）：`tests/system_smoke.py` 两处导出断言改为期待脱敏值。

### AC5 明文显式授权
- `GET /api/insured/export?plaintext=true` 与 `GET /api/policies/{id}/export?plaintext=true`：仅 admin（insured.py:139、reports.py:293），每次写一条 `export_plaintext` audit（insured.py:178-180、reports.py:313-315，含操作者与范围，审计内容本身不含明文证件号）。

### AC6 openapi read_sensitive
- `services/openapi/serializers.py:141` 与 `routers/openapi/policies.py:140` 明文分支改为 `decrypted_id_number()`，端点/scope 语义与 P1 不变（compliance smoke 抹掉明文列后 sensitive 读仍返回原值验证确实走 cipher）。

### AC7 密钥轮换
- `routers/openapi_admin.py:169` `POST /api/openapi-clients/{id}/rotate`（admin）：新 secret 一次性明文返回，旧 secret 进 `secondary_secret_encrypted`（P0 预留列）+ `rotated_at`。
- 验签宽限：`services/openapi/auth.py:39` `ROTATION_GRACE_SECONDS=7*24*3600`，auth.py:121-129 宽限期内新旧 secret 均可过 v2 验签，超期旧 secret → 40100。
- 管理端 `web/src/views/openapi/OpenApiClientsPanel.vue`：轮换按钮 + 确认框 + 一次性新 secret 弹窗 + 「旧钥 X 废止」宽限提示（:134-160 及模板）；`web/src/api/openapiAdmin.ts:37`。

### AC8 渗透自查
- 新建 `tests/openapi_security_smoke.py`：签名篡改（method/path/query/body/wrong-secret）、时间戳越界、sign_version 负例、nonce 重放、禁用/过期 key、轮换超期旧钥、scope 缺失、IP 白名单正负例、跨租户六面越权（人员/历史/保单/成员/申报/webhook）、限流 429、脱敏出口无明文、webhook 推送验签正负例。

### AC9/AC1(本地) 测试
- 新建 `tests/openapi_compliance_smoke.py`：隔离 SQLite 迁移+回填幂等、双写三列断言（4 条写路径）、openapi 申报双写、遗留明文行回退读、hash 查重、明文等值查询静态扫描、导出脱敏/明文审计、cipher-only 读、轮换宽限/超期、全出口（请求日志/审计/webhook/申报 reason/导出错误）无明文扫描。

### AC10
- **未删明文列**（属 story-p3b-drop-plaintext-id-number，不在本次）；明文列仅由双写维护，`decrypted_id_number()` 的 fallback 即观察期回滚通道。

## 修改文件清单

新增：`backend/migrations_alembic/versions/e5f8a1c3d7b2_openapi_p3_compliance.py`、`tests/openapi_compliance_smoke.py`、`tests/openapi_security_smoke.py`。
修改（backend）：`core/migrations.py`、`models/insured.py`、`routers/{insured,enrollment,reports,openapi_admin}.py`、`routers/openapi/policies.py`、`services/{serialization,claims,employment_matching,insurer_settlement,timeliness_reporting}.py`、`services/openapi/{auth,declarations,serializers}.py`。
修改（web）：`src/api/openapiAdmin.ts`、`src/views/openapi/OpenApiClientsPanel.vue`。
修改（tests）：`openapi_{foundation,readonly,write}_smoke.py`（迁移隔离 fixture 补 `insured_people` 最小桩；write 的 head 断言 P2→P3）、`system_smoke.py`（两处导出断言改脱敏，AC4 条款）。

## 测试门槛（最终一轮，全部真实 exit code）

| 命令 | EXIT |
|---|---|
| python3 tests/openapi_compliance_smoke.py | 0 |
| python3 tests/openapi_security_smoke.py | 0 |
| python3 tests/openapi_foundation_smoke.py | 0 |
| python3 tests/openapi_readonly_smoke.py | 0 |
| python3 tests/openapi_write_smoke.py | 0 |
| python3 tests/system_smoke.py | 0 |
| python3 tests/security_smoke.py | 0 |
| python3 tests/participation_lock_smoke.py | 0 |
| python3 tests/recharge_smoke.py | 0 |
| python3 tests/enroll_payment_test.py | 0 |
| python3 tests/insured_draft_enroll_test.py | 0 |
| python3 tests/id_number_test.py | 0 |
| python3 tests/insured_cross_enterprise_uniqueness_test.py | 0 |
| python3 tests/policy_export_fix_test.py（额外，因其直呼 export_policy） | 0 |
| python3 -m compileall -q backend | 0 |
| git diff --check | 0 |
| python3 -m alembic heads → 单 head `e5f8a1c3d7b2` | 0 |
| cd web && npm run build | 0 |

## 返工记录（review-p3 changes_requested → attempt 2，2026-11-22）

评审报告：`../../docs/stories/review-p3.md`（blueprint 根）。Blocker-1（生产 PG16 模板验证）属 run 级动作，由 orchestrator 执行，不在本返工范围。

| 发现 | 修复位置 | 验证 |
|---|---|---|
| **B2** 回填在缺省/错误密钥下静默投毒不可自愈 | `backend/core/migrations.py`：`backfill_insured_id_numbers` 入口加守卫——连接方言非 SQLite 且 `ID_ENCRYPTION_KEY == _DEV_ID_KEY` 时 `RuntimeError`（文案指引设置正式密钥）；迁移 `e5f8a1c3d7b2` docstring 补运维注意 | compliance smoke 新增 `test_backfill_refuses_dev_key_on_non_sqlite`：假 PG dialect + 开发缺省密钥 → 断言在执行任何 SQL 前 raise；换正式密钥 → 守卫放行 |
| **M1** 时效页证件号搜索框承诺「支持模糊查询」 | `web/src/views/timeliness/TimelinessView.vue`：placeholder 改「输入完整证件号精确查询」（宽度 160→180） | `npm run build` EXIT=0 |
| m1 解密失败静默吞异常 | `backend/models/insured.py`：`decrypted_id_number` 失败时 `logger.warning`（person id，不含明文/密文本体，每进程一次），保留回退 | compileall + 全回归 |
| m2 回填与 ORM 事件纯空白口径不一致 | 回填 WHERE 改 `TRIM(id_number) != ''` | compliance/foundation/readonly/write smoke 迁移测试全绿 |
| m3 「分批不分事务」docstring 误导 | docstring 改为如实描述（Alembic 下单事务，分批仅避免全表进内存）；**生产行数评估**：合并前由 orchestrator 在生产库跑 `SELECT count(*) FROM insured_people WHERE TRIM(id_number) != ''`——万级以下照跑；十万级以上建议 executemany 批量 UPDATE 并把索引改 CONCURRENTLY 单独迁移（本次未改实现，属低代价路线） | — |
| m4 AC8「账户越权」未注明结构性 N/A | `tests/openapi_security_smoke.py` 跨租户测试补账户断言 + 注释：`/openapi/v1/accounts` 无资源 id 参数、按 id 探他企业结构性 N/A，改以「A 响应不含 B 企业名/enterprise_id」断言补齐清单 | security smoke EXIT=0 |
| m5 静态扫描正则面窄 | `tests/openapi_compliance_smoke.py`：扫描扩成 7 个变体（==、反向 ==、like/ilike、in_、startswith、contains） | compliance smoke EXIT=0 |
| m6/m7/m8 | 有意取舍/不拦本次，按评审原文维持；p3b 注意事项已在评审与本文风险区记录 | — |

返工后测试（全部真实 exit code）：openapi_compliance_smoke=0、openapi_security_smoke=0、system_smoke=0、openapi_foundation_smoke=0、openapi_readonly_smoke=0、openapi_write_smoke=0、id_number_test=0、compileall=0、`git diff --check`=0、`alembic heads` 单 head `e5f8a1c3d7b2`、`npm run build`=0。

## 未尽事项与风险

1. **PG16 真库迁移验证未做**（AC1 要求模板临时库 upgrade + 回填数核对）：需要 Neon 凭据，`python3 scripts/pg_migration_check.py`，留给 orchestrator 合并前执行。SQLite 通过不足以证明 PG 可用（见 CLAUDE.md 布尔默认值前车之鉴；本迁移 server_default 用的是 `''` 字符串，无布尔列）。
2. **timeliness 证件号搜索语义变化**：原为 LIKE 子串匹配，现为 hash 精确全号匹配（`services/timeliness_reporting.py:99`）——子串搜索在哈希方案下无法支持，属 spec §5 的必然取舍，前端如有「输前几位搜索」预期需知会。
3. **OpenApiDeclarationItem.id_number 列仍存明文**（申报快照，存储非出口）；出口（查询序列化）已脱敏/解密受控。彻底清理属 p3b 范围，已在 story 风险区记录。
4. **保司邮件/结算/理赔出口改为始终脱敏**：如保司线下流程依赖全号，需走 admin 明文导出（AC5 通道）。
5. **再次轮换会立即废止祖父 secret**（secondary 槽被覆盖）：宽限期内连续轮换两次的场景，最旧 secret 直接失效——符合「单槽」设计，管理端确认文案已提示。
6. 导入模板示例行（张三/340123199001011234）为合成数据，保留。
7. `plaintext` 参数用裸 `bool = False` 且放在签名**末位**而非 `Query(False)`：FastAPI 行为等价（仍是 query 参数），但既有测试直呼端点函数时 `Query` 默认对象为 truthy、位置传参会错位——勿改回。

🤖 Generated with [Orchestrix](https://orchestrix-mcp.youlidao.ai)
