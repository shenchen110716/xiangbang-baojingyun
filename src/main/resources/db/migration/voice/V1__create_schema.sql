CREATE SCHEMA IF NOT EXISTS voice;

-- 语音发单会话。撤回窗口(§5.1 防线③"5 分钟内可语音撤回")需要记住
-- 这一单是什么时候、由谁、发布成了哪个岗位。
CREATE TABLE voice.job_session (
    id            BIGSERIAL PRIMARY KEY,
    caller_user_id BIGINT      NOT NULL,
    org_id        BIGINT      NOT NULL,
    title         VARCHAR(100) NOT NULL,
    headcount     INT         NOT NULL,
    wage_cents    BIGINT      NOT NULL,
    extra         VARCHAR(200),
    status        VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    published_job_id BIGINT,
    published_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA voice TO voice_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA voice TO voice_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA voice TO voice_user;
