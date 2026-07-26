CREATE SCHEMA IF NOT EXISTS content;

CREATE TABLE content.article (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    body         TEXT         NOT NULL,
    category     VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    published_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE content.banner (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(100) NOT NULL,
    image_url  VARCHAR(500) NOT NULL,
    link_url   VARCHAR(500),
    weight     INT          NOT NULL DEFAULT 0,
    status     VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA content TO content_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA content TO content_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA content TO content_user;
