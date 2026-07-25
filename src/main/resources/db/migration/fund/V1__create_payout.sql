CREATE SCHEMA IF NOT EXISTS fund;

CREATE TABLE fund.payout (
    id             BIGSERIAL PRIMARY KEY,
    settlement_id  BIGINT      NOT NULL UNIQUE,
    payee_user_id  BIGINT      NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at        TIMESTAMPTZ,
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA fund TO fund_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fund TO fund_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fund TO fund_user;
