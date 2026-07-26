CREATE SCHEMA IF NOT EXISTS agreement;

CREATE TABLE agreement.agreement (
    id              BIGSERIAL PRIMARY KEY,
    application_id  BIGINT      NOT NULL UNIQUE,
    worker_user_id  BIGINT      NOT NULL,
    org_id          BIGINT      NOT NULL,
    content         TEXT        NOT NULL,
    -- 存证(§6.2"签署留存证与哈希,可回溯,支持纠纷举证")
    content_hash    VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- 身份因子:§6.2"电子签的身份因子(人脸/短信)不可省——这是法律效力要件"
    identity_factor VARCHAR(20),
    provider_ref    VARCHAR(100),
    signed_at       TIMESTAMPTZ,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA agreement TO agreement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA agreement TO agreement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA agreement TO agreement_user;
