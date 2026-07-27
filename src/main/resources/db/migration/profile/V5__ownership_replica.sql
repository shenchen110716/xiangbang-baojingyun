-- 岗位画像只能由该岗位所属组织的法人代表修改(此前谁都能改,
-- 可以把竞品岗位的标签和坐标改掉让它在推荐里消失)。
-- 判断依据要本域自己有:跨域查库是铁律禁止的,所以订阅事件落只读副本。
CREATE TABLE profile.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT      NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);
CREATE TABLE profile.posted_job_ref (
    job_id BIGINT PRIMARY KEY,
    org_id BIGINT NOT NULL
);
CREATE INDEX ix_profile_posted_job_org ON profile.posted_job_ref (org_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA profile TO profile_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA profile TO profile_user;
