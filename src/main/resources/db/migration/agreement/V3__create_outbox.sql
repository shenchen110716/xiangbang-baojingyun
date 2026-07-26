-- agreement 域 outbox。AgreementGenerated/AgreementSigned 丢了:待签通知发不出去、履约域不知道协议已签,而签署是履约完成的前置门禁。
CREATE TABLE agreement.outbox_event (
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

CREATE INDEX ix_agreement_outbox_pending ON agreement.outbox_event (status, id) WHERE status <> 'PUBLISHED';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA agreement TO agreement_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA agreement TO agreement_user;
