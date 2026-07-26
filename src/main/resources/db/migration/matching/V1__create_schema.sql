CREATE SCHEMA IF NOT EXISTS matching;

-- 只读投影(主文档 §5.4.5 R5"引擎维护画像/评价只读副本,不反写")。
-- 坐标/期望薪资允许 NULL:岗位可能只发布了基本信息还没设画像,工人可能只提交了标签
-- 没填期望薪资——评分函数必须能容忍维度缺失,不能把"没填资料"当"完全不匹配"。
CREATE TABLE matching.worker_projection (
    user_id             BIGINT PRIMARY KEY,
    tags                TEXT             NOT NULL DEFAULT '',
    expected_wage_cents BIGINT,
    lat                 DOUBLE PRECISION,
    lon                 DOUBLE PRECISION,
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now()
);

CREATE TABLE matching.job_projection (
    job_id     BIGINT PRIMARY KEY,
    org_id     BIGINT           NOT NULL,
    wage_cents BIGINT           NOT NULL,
    must_tags  TEXT             NOT NULL DEFAULT '',
    nice_tags  TEXT             NOT NULL DEFAULT '',
    lat        DOUBLE PRECISION,
    lon        DOUBLE PRECISION,
    updated_at TIMESTAMPTZ      NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA matching TO matching_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA matching TO matching_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA matching TO matching_user;
