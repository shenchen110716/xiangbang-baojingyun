CREATE SCHEMA IF NOT EXISTS review;

-- 履约完成单的只读副本:§5.3 R1"只有完成的履约单可评"要靠它做校验,
-- 不跨域查 engagement。
CREATE TABLE review.completed_engagement (
    application_id  BIGINT PRIMARY KEY,
    job_id          BIGINT      NOT NULL,
    worker_user_id  BIGINT      NOT NULL,
    org_id          BIGINT      NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL
);

-- 双盲评价(§5.3 R2):visible 控制是否公开。
-- 一单一方一次靠 (application_id, rater_user_id) 唯一约束。
CREATE TABLE review.review (
    id             BIGSERIAL PRIMARY KEY,
    application_id BIGINT      NOT NULL,
    rater_user_id  BIGINT      NOT NULL,
    ratee_user_id  BIGINT,
    ratee_org_id   BIGINT,
    direction      VARCHAR(20) NOT NULL,
    tags           TEXT        NOT NULL DEFAULT '',
    comment        TEXT,
    score          DOUBLE PRECISION NOT NULL,
    visible        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (application_id, rater_user_id)
);

-- 信用分(§5.3.2)。0-100,新人 60 分起。
CREATE TABLE review.credit_score (
    user_id    BIGINT PRIMARY KEY,
    score      DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA review TO review_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA review TO review_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA review TO review_user;
