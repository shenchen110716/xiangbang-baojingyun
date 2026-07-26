-- §5.4 硬约束(过滤)含"名额未满",且"不满足直接不出现,不是降权"。
-- 此前投影没有状态字段,招满/下架的岗位会一直被推荐。
ALTER TABLE matching.job_projection ADD COLUMN open BOOLEAN NOT NULL DEFAULT TRUE;

-- 候选池按 open 过滤 + 按 updated_at 排序取前 N,这个索引正好覆盖那条查询
CREATE INDEX ix_job_projection_open_updated
    ON matching.job_projection (open, updated_at DESC);
