CREATE SCHEMA IF NOT EXISTS talent;

-- 人才档案沉淀(§4.2"人才库 | 档案沉淀复用 | 订阅画像事件")。
-- 价值在**复用**:工厂要找"以前干过的人"或"有某技能且履约记录好的人",
-- 不用每次从零匹配。所以除了标签,还要沉淀累计履约次数与最近活跃时间。
CREATE TABLE talent.talent_profile (
    user_id               BIGINT PRIMARY KEY,
    tags                  TEXT   NOT NULL DEFAULT '',
    expected_wage_cents   BIGINT,
    completed_engagements INT    NOT NULL DEFAULT 0,
    last_active_at        TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA talent TO talent_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA talent TO talent_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA talent TO talent_user;
