-- 平台级角色。§4.2 把"授权"划给身份域,所以角色存在这里,由身份域裁决。
CREATE TABLE identity.user_role (
    user_id    BIGINT      NOT NULL,
    role       VARCHAR(40) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO identity_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA identity TO identity_user;
