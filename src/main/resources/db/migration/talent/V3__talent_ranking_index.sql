-- 人才检索按(履约次数降序, 最近活跃降序)取前 N,此前只有主键。
CREATE INDEX ix_talent_ranking ON talent.talent_profile (completed_engagements DESC, last_active_at DESC);
