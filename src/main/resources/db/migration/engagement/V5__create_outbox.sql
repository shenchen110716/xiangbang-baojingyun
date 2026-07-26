-- 履约域 outbox。EngagementCompleted 扇出五个下游(结算、评价、人才库、画像、通知),
-- 一个都不自愈:不会再有第二次"这单干完了"的事件。丢了 = 工资单不生成。
CREATE TABLE engagement.outbox_event (
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

CREATE INDEX ix_engagement_outbox_pending ON engagement.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA engagement TO engagement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA engagement TO engagement_user;
