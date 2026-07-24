CREATE SCHEMA IF NOT EXISTS org;

CREATE TABLE org.organization (
    id                BIGSERIAL PRIMARY KEY,
    type              VARCHAR(20)  NOT NULL,
    name              VARCHAR(100) NOT NULL,
    credit_code       VARCHAR(32)  NOT NULL UNIQUE,
    legal_rep_user_id BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE org.verified_user (
    user_id     BIGINT PRIMARY KEY,
    real_name   VARCHAR(50) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL
);

GRANT USAGE ON SCHEMA org TO org_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA org TO org_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA org TO org_user;
