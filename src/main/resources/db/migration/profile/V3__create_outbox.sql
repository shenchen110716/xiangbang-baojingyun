-- profile 域 outbox。ProfileUpdated/JobProfileUpdated 丢了:匹配投影停留在旧画像,推荐依据是过期的。
CREATE TABLE profile.outbox_event (
    id            BIGSERIAL PRIMARY KEY,
    event_id      VARCHAR(64)  NOT NULL UNIQUE,
    event_type    VARCHAR(200) NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX ix_profile_outbox_pending ON profile.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA profile TO profile_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA profile TO profile_user;
