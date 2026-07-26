-- 监管账户与资金隔离(§6.4.2)
-- "平台监管账户托管在途资金,平台自有收入与用户资金**分账,不混同**。"
-- 分账不是一个大池子加个标记,而是不同账户各自独立记余额。
CREATE TABLE fund.escrow_account (
    account_type  VARCHAR(20) PRIMARY KEY,
    balance_cents BIGINT      NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 三段式流水(参考 §2.5 AppUserMoneylog):变动前/变动额/变动后。
-- "对账以账本为准"。
CREATE TABLE fund.escrow_ledger (
    id            BIGSERIAL PRIMARY KEY,
    account_type  VARCHAR(20) NOT NULL,
    before_cents  BIGINT      NOT NULL,
    amount_cents  BIGINT      NOT NULL,
    after_cents   BIGINT      NOT NULL,
    reason        VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 微工卡代发(§6.4.2)
-- idempotency_key 唯一:"每笔代发带唯一单号...失败可重发",重发不能重复打钱
CREATE TABLE fund.disbursement (
    id                  BIGSERIAL PRIMARY KEY,
    payout_id           BIGINT       NOT NULL,
    payee_user_id       BIGINT       NOT NULL,
    amount_cents        BIGINT       NOT NULL,
    idempotency_key     VARCHAR(100) NOT NULL UNIQUE,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    external_ref        VARCHAR(100),
    -- 完税凭证:§6.4.2"平台代征代缴,出完税凭证"。税务合规是规模化的生死线
    tax_certificate_no  VARCHAR(100),
    fail_reason         VARCHAR(300),
    retry_count         INT          NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO fund.escrow_account (account_type, balance_cents) VALUES
    ('USER_FUNDS', 0), ('PLATFORM_REVENUE', 0), ('GUARANTEE_POOL', 0);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fund TO fund_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fund TO fund_user;

-- 信用分只读副本(担保策略要用,不跨域查评价域)
CREATE TABLE fund.worker_credit (
    user_id    BIGINT PRIMARY KEY,
    score      DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fund TO fund_user;
