-- 匹配 v1(§5.4.2):信用维度。允许 NULL——没有信用分记录的用户按新人处理。
ALTER TABLE matching.worker_projection ADD COLUMN credit_score DOUBLE PRECISION;
