-- 人才候选池按 updated_at 倒序取前 N。岗位侧建了 ix_job_projection_open_updated,
-- 人才侧漏了 —— 同一条查询模式,一边有索引一边全表扫 + 外部排序。
CREATE INDEX ix_worker_projection_updated ON matching.worker_projection (updated_at DESC);
