-- 岗位关闭后不能再报名。投影订阅 JobClosed 落这个标记。
-- 存量行默认 TRUE:此前根本没有关闭这回事,历史岗位都还开着。
ALTER TABLE engagement.posted_job ADD COLUMN open BOOLEAN NOT NULL DEFAULT TRUE;
