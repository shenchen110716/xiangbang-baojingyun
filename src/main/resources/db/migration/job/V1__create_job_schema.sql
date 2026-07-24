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

CREATE TABLE job.job (
    id          BIGSERIAL PRIMARY KEY,
    org_id      BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL,
    description TEXT         NOT NULL,
    wage_cents  BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE job.application (
    id                 BIGSERIAL PRIMARY KEY,
    job_id             BIGINT      NOT NULL,
    applicant_user_id  BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA job TO job_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA job TO job_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA job TO job_user;
