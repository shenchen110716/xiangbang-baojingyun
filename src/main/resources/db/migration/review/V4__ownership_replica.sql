-- 提交评价此前不校验"你是不是这单的当事人",任何人都能以工厂身份
-- 给任意工人差评、直接砸信用分。判断要用到"谁是这个组织的法人代表",
-- 本域订阅事件落只读副本。
CREATE TABLE review.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT      NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA review TO review_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA review TO review_user;
