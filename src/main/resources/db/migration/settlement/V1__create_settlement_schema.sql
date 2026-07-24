CREATE SCHEMA IF NOT EXISTS settlement;

CREATE TABLE settlement.settlement (
    id                 BIGSERIAL PRIMARY KEY,
    application_id     BIGINT      NOT NULL UNIQUE,
    job_id             BIGINT      NOT NULL,
    worker_user_id     BIGINT      NOT NULL,
    amount_cents       BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    void_reason        VARCHAR(200),
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA settlement TO settlement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA settlement TO settlement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA settlement TO settlement_user;
