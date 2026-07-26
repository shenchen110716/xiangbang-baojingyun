-- 资金域的 outbox。FundsDisbursed 一丢,经纪人佣金不生成、盈亏账少一笔、
-- 工人收不到到账通知——三个下游全是"不会自愈"的后果(不会再有第二次发放事件)。
CREATE TABLE fund.outbox_event (
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

CREATE INDEX ix_fund_outbox_pending ON fund.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fund TO fund_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fund TO fund_user;
