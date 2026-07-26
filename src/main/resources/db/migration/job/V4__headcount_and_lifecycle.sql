-- §4.2 岗位域职责:"发布、生命周期、名额、快照",独立性关键是"名额扣减在本域闭环"。
-- 此前岗位连名额字段都没有,Status.CLOSED 也从没被任何代码设置过。
ALTER TABLE job.job ADD COLUMN headcount    INT    NOT NULL DEFAULT 1;
ALTER TABLE job.job ADD COLUMN filled_count INT    NOT NULL DEFAULT 0;
ALTER TABLE job.job ADD COLUMN closed_at    TIMESTAMPTZ;
-- 乐观锁:名额扣减是并发点(多个法人代表同时录用),没有它两个人能同时读到"还剩 1 个"
ALTER TABLE job.job ADD COLUMN version      BIGINT NOT NULL DEFAULT 0;

-- 兜底不变式。应用层已经挡了超额,但名额直接对应"要付几份工资",
-- 值得让数据库也拒绝一次——应用层的检查会被下一个人改掉,约束不会。
ALTER TABLE job.job ADD CONSTRAINT ck_job_headcount_positive CHECK (headcount >= 1);
ALTER TABLE job.job ADD CONSTRAINT ck_job_filled_within_headcount
    CHECK (filled_count >= 0 AND filled_count <= headcount);
