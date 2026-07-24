CREATE TABLE broker.commission (
    id             BIGSERIAL PRIMARY KEY,
    broker_user_id BIGINT      NOT NULL,
    worker_user_id BIGINT      NOT NULL,
    settlement_id  BIGINT      NOT NULL UNIQUE,
    amount_cents   BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version        BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA broker TO broker_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA broker TO broker_user;
