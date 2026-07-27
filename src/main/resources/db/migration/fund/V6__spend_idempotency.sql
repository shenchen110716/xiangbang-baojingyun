-- 出账幂等键。
--
-- 之前 spendFromAccount 没有任何幂等标识,而调用方(报销域)与资金域是两个事务:
-- 钱已经出去、调用方那边再失败回滚,单据退回待审批,可以再审批再扣一次;
-- 而 escrow_ledger 里没有任何业务键,事后**无法识别哪一笔是重复的**。
ALTER TABLE fund.escrow_ledger ADD COLUMN idempotency_key VARCHAR(100);
CREATE UNIQUE INDEX ux_escrow_ledger_idempotency
    ON fund.escrow_ledger (idempotency_key) WHERE idempotency_key IS NOT NULL;
