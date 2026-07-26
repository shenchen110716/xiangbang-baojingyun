CREATE SCHEMA IF NOT EXISTS ops;

-- §4.2 运营:审核、字典、RBAC、看板。
-- 先做字典——画像域的受控词表硬编码就是在等这个运营侧抓手(Plan6/8 记过)。
CREATE TABLE ops.dictionary_item (
    id         BIGSERIAL PRIMARY KEY,
    dict_type  VARCHAR(50)  NOT NULL,
    item_key   VARCHAR(100) NOT NULL,
    item_value VARCHAR(200) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (dict_type, item_key)
);

GRANT USAGE ON SCHEMA ops TO ops_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ops TO ops_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ops TO ops_user;
