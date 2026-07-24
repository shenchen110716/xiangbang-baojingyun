CREATE SCHEMA IF NOT EXISTS job;

CREATE TABLE job.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE job.verified_user (
    user_id     BIGINT PRIMARY KEY,
    verified_at TIMESTAMPTZ NOT NULL
);

GRANT USAGE ON SCHEMA job TO job_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA job TO job_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA job TO job_user;
