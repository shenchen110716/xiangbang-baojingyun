-- 匹配引擎 v0(Plan8)所需的结构化要素:岗位画像(must/nice 标签 + 坐标)与人才侧偏好(期望薪资 + 坐标)。
-- 按主文档 §4.2,岗位画像归画像域("画像 | 人才/岗位画像"),不新开域。

CREATE TABLE profile.job_profile (
    job_id     BIGINT PRIMARY KEY,
    must_tags  TEXT             NOT NULL DEFAULT '',
    nice_tags  TEXT             NOT NULL DEFAULT '',
    lat        DOUBLE PRECISION NOT NULL,
    lon        DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE profile.worker_preference (
    user_id             BIGINT PRIMARY KEY,
    expected_wage_cents BIGINT           NOT NULL,
    lat                 DOUBLE PRECISION NOT NULL,
    lon                 DOUBLE PRECISION NOT NULL,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA profile TO profile_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA profile TO profile_user;
