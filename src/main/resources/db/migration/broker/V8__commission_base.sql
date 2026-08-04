-- 佣金基数的只读副本。
--
-- 老系统 JobComputerService 拿**浮动工资**(floatSalary)算佣金,不是拿应发总额。
-- 我们此前用的是发放总额 —— 那会让佣金随基本工资一起涨,和老系统的口径不一致。
--
-- 基数从结算域的 SettlementCalculated 事件流过来,在这里落副本;
-- 等 FundsDisbursed(钱真的付了)到达时再按它分账。
-- 两个事件的到达顺序不保证,所以要存下来而不是指望"发放时结算事件已经处理过"。
CREATE TABLE broker.commission_base (
    settlement_id BIGINT PRIMARY KEY,
    base_cents    BIGINT      NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT commission_base_nonneg_ck CHECK (base_cents >= 0)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON broker.commission_base TO broker_user;
