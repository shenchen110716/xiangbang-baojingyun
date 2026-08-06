-- 总价单的分账**在发单时定死**(老板 2026-08-06 的公式)。
--
-- 为什么不在结算时算:工人是看着"这单 900 元"才接的。
-- 中途有人改了「类目 + 地区」的佣金比例,结算时重算会让他拿到手变成 850 ——
-- **承诺过的价钱不该被事后改动**,而且他不会知道为什么少了。
--
-- 顺带这也避开了一个死结:比例配置在经纪人域,而经纪人域依赖结算域,
-- 结算域再反向依赖经纪人域就成环了。发单时算则是 job → broker,不成环。

ALTER TABLE job.job
    -- 员工价:工人实际拿到的。总价 − 佣金总额
    ADD COLUMN worker_cents         BIGINT,
    -- 佣金总额:总价 × 佣金比例
    ADD COLUMN commission_cents     BIGINT,
    -- 派遣公司留存:佣金总额 × 派遣留存比例
    ADD COLUMN dispatch_retain_cents BIGINT,
    -- 收这笔留存的派遣公司。留存为 0 时为 null
    ADD COLUMN dispatch_org_id      BIGINT;

-- **三段加起来必须正好是总价。**少一分就是有一分钱没有归属,
-- 而对账时没人认领的钱最难查。放在数据库上是因为应用层那道校验漏一次没有症状
ALTER TABLE job.job
    ADD CONSTRAINT job_price_split_exact CHECK (
        total_price_cents IS NULL OR
        worker_cents + dispatch_retain_cents + (commission_cents - dispatch_retain_cents)
            = total_price_cents);
