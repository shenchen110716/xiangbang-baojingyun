CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.app_user (
    id              BIGSERIAL PRIMARY KEY,
    phone           VARCHAR(20)  NOT NULL UNIQUE,
    real_name       VARCHAR(50),
    id_number       VARCHAR(32)  UNIQUE,
    verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    blocked         BOOLEAN      NOT NULL DEFAULT FALSE,
    invite_code     VARCHAR(16)  NOT NULL UNIQUE,
    balance_cents   BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_app_user_phone ON identity.app_user(phone);

-- 铁律 1:只把本域 schema 授权给本域用户,不授予任何其它 schema
GRANT USAGE ON SCHEMA identity TO identity_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO identity_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO identity_user;
