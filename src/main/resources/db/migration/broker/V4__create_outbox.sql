-- broker 域 outbox。CommissionGenerated 丢了:经纪人佣金不进盈亏账,本人也收不到通知。
CREATE TABLE broker.outbox_event (
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

CREATE INDEX ix_broker_outbox_pending ON broker.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA broker TO broker_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA broker TO broker_user;
