-- findByPayoutId 是代发主链路上的幂等检查,每次发放都要走一次,却没有索引。
CREATE INDEX ix_disbursement_payout ON fund.disbursement (payout_id);
