-- 个人发单 + 总价模式(老板 2026-08-06)。
--
--   员工价     = 总价 − 佣金总额
--   佣金总额   = 总价 × 佣金比例(类目 + 地区)
--   派遣公司留存 = 佣金总额 × 派遣留存比例
--
-- 比例配在 broker.commission_rate 上,已经建好并验过。这一批做**发单这一侧**。

-- ─────────────── 发单方 ───────────────
--
-- 岗位原来必须挂在已审核的组织下。个人发单没有组织 ——
-- **不给他造一个"个人组织"**:organization 上已经有一串针对企业的约束
-- (信用代码、法人、审核状态),硬塞进去要么放宽那些约束、要么填假数据,
-- 两条都会污染企业那一侧。

ALTER TABLE job.job ALTER COLUMN org_id DROP NOT NULL;
ALTER TABLE job.job ADD COLUMN poster_user_id BIGINT;

-- **恰好一个。**两个都填的话"这单归谁"要靠取数时的优先级决定,
-- 而那种优先级散在各处,迟早两边写得不一样;两个都空则是一张没有主人的单子,
-- 结算时不知道找谁要钱
ALTER TABLE job.job
    ADD CONSTRAINT job_poster_exactly_one CHECK (
        (org_id IS NOT NULL AND poster_user_id IS NULL) OR
        (org_id IS NULL AND poster_user_id IS NOT NULL));

CREATE INDEX ix_job_poster ON job.job (poster_user_id) WHERE poster_user_id IS NOT NULL;

-- ─────────────── 总价与地区 ───────────────

-- 总价模式:发单方只填一个总数,员工价和佣金由平台按比例算出来。
-- **和 wage_cents 并存而不是替换** —— 企业按小时/按天计薪那条路还在用,
-- 换掉的话所有存量岗位都要重算,而它们的口径本来就不是总价。
ALTER TABLE job.job ADD COLUMN total_price_cents BIGINT;

-- 国标行政区划代码。佣金比例按 类目 + 地区 配,**取不到地区就取不到比例**。
--
-- **必须是选出来的,不能从工作地址文本里解析。**"苏州市吴中区…"解析错了
-- 不会报错,只会静默套上另一个地区的比例,而那要对账才发现。
ALTER TABLE job.job ADD COLUMN region_code VARCHAR(6);

-- 总价必须为正。0 元的单子没有业务含义,负数更是会算出负佣金
ALTER TABLE job.job
    ADD CONSTRAINT job_total_price_positive CHECK (
        total_price_cents IS NULL OR total_price_cents > 0);

-- **个人发单必须走总价、必须填地区。**
-- 个人不会做计薪方案(那要考勤、工时、加班系数),总价是他唯一能表达的口径;
-- 没有地区就取不到佣金比例,这单结算时会卡住 —— 与其那时候报错,不如发单时就拦。
ALTER TABLE job.job
    ADD CONSTRAINT job_individual_needs_total_price CHECK (
        poster_user_id IS NULL OR (total_price_cents IS NOT NULL AND region_code IS NOT NULL));
