-- outbox 放在发布方(结算域)自己的 schema 里,不是共享表——
-- 事件行必须和业务数据在同一个事务落库,而每个域有独立 DataSource,
-- 共享 schema 会变成跨事务,outbox 的保证就没了(详见 AbstractOutboxEvent 注释)。
CREATE TABLE settlement.outbox_event (
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

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA settlement TO settlement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA settlement TO settlement_user;
