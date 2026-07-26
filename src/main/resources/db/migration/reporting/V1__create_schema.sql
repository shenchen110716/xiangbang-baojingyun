CREATE SCHEMA IF NOT EXISTS reporting;

-- §6.6.1 铁律:"报表域**订阅各域事件**,在本域内构建宽表只读模型。
-- **绝不跨域 join 生产表**——否则报表一慢就拖垮交易,又回到旧系统
-- '一个大查询锁全库'的老路。"
CREATE TABLE reporting.ledger_fact (
    id            BIGSERIAL PRIMARY KEY,
    dimension     VARCHAR(20)  NOT NULL,   -- WORKER / BROKER / ORG
    dimension_id  BIGINT       NOT NULL,
    entry_type    VARCHAR(20)  NOT NULL,   -- REVENUE / DIRECT_COST
    amount_cents  BIGINT       NOT NULL,
    source        VARCHAR(40)  NOT NULL,
    reference_id  BIGINT,
    occurred_at   TIMESTAMPTZ  NOT NULL,
    UNIQUE (dimension, dimension_id, source, reference_id)
);

-- 公共费用(房租/运营/系统),待按基准分摊到各维度
CREATE TABLE reporting.overhead (
    id             BIGSERIAL PRIMARY KEY,
    label          VARCHAR(100) NOT NULL,
    amount_cents   BIGINT       NOT NULL,
    allocation_basis VARCHAR(20) NOT NULL,  -- HEADCOUNT / REVENUE_SHARE / WORK_HOURS
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA reporting TO reporting_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reporting TO reporting_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA reporting TO reporting_user;
