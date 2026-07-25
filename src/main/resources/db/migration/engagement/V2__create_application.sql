CREATE TABLE engagement.application (
    id                 BIGSERIAL PRIMARY KEY,
    job_id             BIGINT      NOT NULL,
    applicant_user_id  BIGINT      NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA engagement TO engagement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA engagement TO engagement_user;
