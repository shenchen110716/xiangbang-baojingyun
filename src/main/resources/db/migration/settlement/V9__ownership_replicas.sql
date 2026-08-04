-- 归属校验用的只读副本。铁律 3:需要他域数据时订阅事件、在本域维护副本,不跨域查库。
--
-- 计薪方案要能在**发岗之后、有人录用之前**就设好,
-- 所以副本来源是 JobPosted(带 orgId)而不是 ApplicationAccepted —— 后者要等有人被录用。

CREATE TABLE settlement.posted_job (
    job_id    BIGINT PRIMARY KEY,
    org_id    BIGINT NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX posted_job_org_idx ON settlement.posted_job (org_id);

CREATE TABLE settlement.approved_org (
    org_id            BIGINT PRIMARY KEY,
    legal_rep_user_id BIGINT NOT NULL,
    approved_at       TIMESTAMPTZ NOT NULL
);

GRANT SELECT, INSERT, UPDATE, DELETE ON settlement.posted_job   TO settlement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON settlement.approved_org TO settlement_user;
