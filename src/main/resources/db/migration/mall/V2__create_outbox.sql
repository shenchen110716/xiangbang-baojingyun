-- mall 域 outbox。OrderSettlementTriggered 丢了:核销了却不结算,商户拿不到钱。
CREATE TABLE mall.outbox_event (
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

CREATE INDEX ix_mall_outbox_pending ON mall.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mall TO mall_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA mall TO mall_user;
