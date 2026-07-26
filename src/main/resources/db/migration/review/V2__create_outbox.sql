-- review 域 outbox。CreditScoreChanged 丢了:匹配与资金域用的还是旧信用分,担保策略据此决定要不要收押金。
CREATE TABLE review.outbox_event (
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

CREATE INDEX ix_review_outbox_pending ON review.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA review TO review_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA review TO review_user;
