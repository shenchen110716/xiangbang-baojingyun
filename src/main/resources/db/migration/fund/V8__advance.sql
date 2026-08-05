-- 借支与还款(老系统 M8「借押保」的 JobBorrow / JobBorrowBack)。
--
-- 老系统的规则:借支不超可用额度、每笔借支与还款留痕、发工资时从工资直接抵扣
-- (实发 = 各项合计 − 借支合计)。
--
-- **为什么在资金域而不是结算域。**Plan13 已定「押金+借支归一,都收进资金域」,
-- 而这里还有一条硬约束:资金域订阅结算域的事件,结算域再反向依赖资金域就成环,
-- ModularityTests 会直接拦下。这个限制恰好和业务对上 ——
-- 结算域算的是**应发**(这个人这段时间干了多少活),它不该知道这人欠平台多少钱;
-- 资金域算的是**实发**(实际打出去多少)。两件事本来就该分开。

CREATE TABLE fund.advance (
    id                  BIGSERIAL PRIMARY KEY,
    worker_user_id      BIGINT      NOT NULL,
    amount_cents        BIGINT      NOT NULL,
    -- 未还金额。抵扣时递减,归零即结清
    outstanding_cents   BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason              VARCHAR(200),
    -- 谁批的。借支是**平台把钱先垫给工人**,不是工人自助操作
    granted_by          BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cleared_at          TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,

    -- 金额必须为正:借 0 元或负数没有业务含义,放进来只会让后面每一处都要防它
    CONSTRAINT advance_amount_positive CHECK (amount_cents > 0),
    -- **未还金额不能为负,也不能超过本金。**
    -- 负数意味着"还多了",那是账错了;超过本金意味着凭空长出债务。
    -- 这两件事在应用层都能防,但防错了没有任何症状 —— 所以让数据库兜底
    CONSTRAINT advance_outstanding_range CHECK (outstanding_cents >= 0
                                                AND outstanding_cents <= amount_cents),
    CONSTRAINT advance_status_valid CHECK (status IN ('ACTIVE', 'CLEARED', 'CANCELLED'))
);

-- 查某人的未还借支是主路径(每次发工资都要查)
CREATE INDEX advance_worker_status_idx ON fund.advance (worker_user_id, status);

CREATE TABLE fund.advance_repayment (
    id              BIGSERIAL PRIMARY KEY,
    advance_id      BIGINT      NOT NULL REFERENCES fund.advance (id),
    amount_cents    BIGINT      NOT NULL,
    -- SALARY_DEDUCTION = 发工资时自动扣;MANUAL = 线下还款后人工登记
    source          VARCHAR(20) NOT NULL,
    -- 工资抵扣时对应的结算单。人工还款为空
    settlement_id   BIGINT,
    recorded_by     BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT repayment_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT repayment_source_valid CHECK (source IN ('SALARY_DEDUCTION', 'MANUAL'))
);

-- **同一笔借支在同一张结算单上只能扣一次。**
-- 中继是至少一次投递,同一条 SettlementCalculated 会重复到达;
-- 少了这条约束,重复投递就是重复扣钱 —— 而工人只会看到"钱怎么少了",
-- 查不出是被扣了两次。幂等键用业务键(借支+结算单),不是 eventId。
CREATE UNIQUE INDEX repayment_once_per_settlement_idx
    ON fund.advance_repayment (advance_id, settlement_id)
    WHERE settlement_id IS NOT NULL;

CREATE INDEX repayment_advance_idx ON fund.advance_repayment (advance_id);

-- 铁律 1:新表要显式授权。
-- GRANT ON ALL TABLES 只覆盖执行那一刻已存在的表,后建的表不会自动获得权限 ——
-- 这个坑在 ops V6 上踩过一次,当时是 SchemaIsolationTests 抓出来的
GRANT SELECT, INSERT, UPDATE, DELETE ON fund.advance TO fund_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON fund.advance_repayment TO fund_user;
GRANT USAGE, SELECT ON SEQUENCE fund.advance_id_seq TO fund_user;
GRANT USAGE, SELECT ON SEQUENCE fund.advance_repayment_id_seq TO fund_user;
