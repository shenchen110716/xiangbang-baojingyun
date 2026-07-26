-- 累计履约次数原本是"来一次事件加一次"。同步单次投递时看不出问题,
-- 但事件改走 outbox 后语义是**至少一次**,重投就会把次数刷高——
-- 而人才库的排序(和候选池截断时留下谁)正是按这个次数排的。
-- 记下已计入的履约单,靠主键去重。
CREATE TABLE talent.counted_engagement (
    application_id BIGINT PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    counted_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA talent TO talent_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA talent TO talent_user;
