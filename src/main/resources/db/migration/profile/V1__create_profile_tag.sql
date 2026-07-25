CREATE SCHEMA IF NOT EXISTS profile;

CREATE TABLE profile.profile_tag (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    tag_name      VARCHAR(50)  NOT NULL,
    source        VARCHAR(20)  NOT NULL DEFAULT 'SELF_REPORTED',
    confidence    DOUBLE PRECISION NOT NULL DEFAULT 0.4,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, tag_name)
);

GRANT USAGE ON SCHEMA profile TO profile_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA profile TO profile_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA profile TO profile_user;
