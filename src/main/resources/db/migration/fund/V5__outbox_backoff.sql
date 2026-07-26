-- 投递失败后要退避,不能每轮都重投。
-- 原来的取行条件是 status <> 'PUBLISHED',于是一个永久失败的事件会每 5 秒被重试一次,
-- 而且因为按 id 升序取、每批只取 100 行,足够多的坏事件会把批次头部占满,
-- 后面正常的事件永远排不上——队头阻塞。
ALTER TABLE fund.outbox_event ADD COLUMN next_attempt_at TIMESTAMPTZ;

DROP INDEX IF EXISTS fund.ix_fund_outbox_pending;
CREATE INDEX ix_fund_outbox_due ON fund.outbox_event (next_attempt_at, id)
    WHERE status <> 'PUBLISHED';
