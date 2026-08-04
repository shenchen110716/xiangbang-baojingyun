-- 六档佣金分账。此前一笔发放只生成**一条** 10% 的佣金,
-- 而老系统的模型是同一笔钱分给多方:主动(直接邀请人)、被动(沿链逐级)、服务站、平台。
--
-- 分账口径(照搬老系统 JobComputerService):
--   基数     = 本次发放金额
--   主动     = 基数 × 主动比例            → 直接经纪人
--   剩余     = 基数 − 主动
--     平台   = 剩余 × 平台比例            → 记入资金域 PLATFORM_REVENUE,不进本表
--     被动池 = 剩余 × 被动比例            → 沿经纪人树向上逐级分,每级拿走当前剩余 × 逐级比例
--     服务站 = 剩余 × 服务站比例          → 直接经纪人所属服务站
--
-- 被动逐级是**递减**的:越往上越少,低于下限即停止 —— 否则会产生大量一分钱的流水。

ALTER TABLE broker.commission
    -- ACTIVE 主动 / PASSIVE 被动 / STATION 服务站
    ADD COLUMN tier VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    -- 服务站分账的收款方是站,不是某个人
    ADD COLUMN station_org_id BIGINT,
    -- 被动分账在链上的层级(1 = 直接经纪人的上级)。主动与服务站为 0
    ADD COLUMN chain_depth INT NOT NULL DEFAULT 0;

ALTER TABLE broker.commission
    ADD CONSTRAINT commission_tier_ck CHECK (tier IN ('ACTIVE', 'PASSIVE', 'STATION'));

-- 收款方恰好有一个:人或站,不能都有也不能都没有。
-- 少了这条,一条既没有人也没有站的佣金会静静躺在表里,永远没人来领。
ALTER TABLE broker.commission
    ADD CONSTRAINT commission_payee_ck CHECK (
        (tier = 'STATION' AND station_org_id IS NOT NULL AND broker_user_id IS NULL)
     OR (tier <> 'STATION' AND station_org_id IS NULL     AND broker_user_id IS NOT NULL));

-- broker_user_id 原来是 NOT NULL,服务站分账没有它
ALTER TABLE broker.commission ALTER COLUMN broker_user_id DROP NOT NULL;

-- 原来的 settlement_id UNIQUE 是"一笔结算只生成一条佣金"的保证,
-- 现在一笔结算要生成多条(不同收款方、不同档),那个约束必须换掉。
-- **换成更细的唯一键而不是直接删** —— 删掉就等于把重复投递的防线撤了,
-- 而投递是至少一次的。
ALTER TABLE broker.commission DROP CONSTRAINT IF EXISTS commission_settlement_id_key;
DROP INDEX IF EXISTS broker.commission_settlement_id_key;

CREATE UNIQUE INDEX commission_unique_payee_idx ON broker.commission (
    settlement_id, tier, chain_depth,
    COALESCE(broker_user_id, -1), COALESCE(station_org_id, -1));

CREATE INDEX commission_settlement_idx ON broker.commission (settlement_id);
