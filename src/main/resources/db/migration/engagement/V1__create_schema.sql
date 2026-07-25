CREATE SCHEMA IF NOT EXISTS engagement;

CREATE TABLE engagement.verified_user (
    user_id     BIGINT PRIMARY KEY,
    verified_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE engagement.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE engagement.posted_job (
    job_id     BIGINT PRIMARY KEY,
    org_id     BIGINT NOT NULL,
    wage_cents BIGINT NOT NULL
);

GRANT USAGE ON SCHEMA engagement TO engagement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA engagement TO engagement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA engagement TO engagement_user;
