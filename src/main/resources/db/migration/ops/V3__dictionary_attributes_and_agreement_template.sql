-- 结构化属性:技能词表是纯键值,评价标签不是——它还带"正面/负面"和"严重度"。
-- 用一列 JSON 存扩展属性,而不是给每种词表加专用列:字典本来就是通用设施。
-- 存 TEXT 不存 JSONB:这里从不按属性查询,只整块读出来解析,JSONB 的索引能力用不上,
-- 反而要给映射层加类型处理。哪天需要按属性检索了再改列类型。
ALTER TABLE ops.dictionary_item ADD COLUMN attributes TEXT;

-- §6.2"协议内容由模板 + 变量生成,模板由运营维护、法务审定"。
-- 版本化不是锦上添花:协议签了之后模板还会改,纠纷举证时必须能翻出**当时那一版**。
CREATE TABLE ops.agreement_template (
    id           BIGSERIAL PRIMARY KEY,
    template_key VARCHAR(50)  NOT NULL,
    version      INT          NOT NULL,
    body         TEXT         NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (template_key, version)
);

-- 同一个 key 最多只能有一版在生效。用部分唯一索引让数据库来保证,
-- 而不是指望应用层每次都记得把上一版下架。
CREATE UNIQUE INDEX ux_agreement_template_active
    ON ops.agreement_template (template_key) WHERE active;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ops TO ops_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ops TO ops_user;
