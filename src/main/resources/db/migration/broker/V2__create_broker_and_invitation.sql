CREATE TABLE broker.broker (
    user_id       BIGINT PRIMARY KEY,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE broker.invitation (
    id             BIGSERIAL PRIMARY KEY,
    broker_user_id BIGINT      NOT NULL,
    worker_user_id BIGINT      NOT NULL UNIQUE,
    bound_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA broker TO broker_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA broker TO broker_user;
