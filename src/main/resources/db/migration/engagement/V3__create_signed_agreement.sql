-- 已签署协议的本域只读副本:completeApplication 的门禁要用(§6.2 前置门禁),
-- 不跨域查 agreement。
CREATE TABLE engagement.signed_agreement (
    application_id BIGINT PRIMARY KEY,
    content_hash   VARCHAR(64) NOT NULL,
    signed_at      TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA engagement TO engagement_user;
