-- 信用分重算按人查历史。这两张是只增不删的历史表,没有索引时每次重算都是全表扫描,
-- 而重算挂在最高频的事件上(每次履约完成、每收到一条评价)。
CREATE INDEX ix_completed_engagement_worker ON review.completed_engagement (worker_user_id);
CREATE INDEX ix_review_ratee ON review.review (ratee_user_id);
