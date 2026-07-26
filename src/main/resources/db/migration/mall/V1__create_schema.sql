CREATE SCHEMA IF NOT EXISTS mall;

-- §6.3 核销型电商:景区门票/演出票/酒店特惠房。平台不囤货不发货。
-- 一件"商品"本质是**一张待售的凭证**,而非可无限补货的实物(§6.3.3)。
CREATE TABLE mall.product (
    id                BIGSERIAL PRIMARY KEY,
    merchant_id       BIGINT       NOT NULL,
    title             VARCHAR(200) NOT NULL,
    -- §6.3.2 两类商品:即时结算(概不退货) vs 核销结算(可退)
    settlement_mode   VARCHAR(20)  NOT NULL,
    price_cents       BIGINT       NOT NULL,
    -- 场次库存:按日期/场次划分的限量单位(§6.3.1"7月20日 池座 100 张")
    session_label     VARCHAR(100) NOT NULL,
    stock             INT          NOT NULL,
    -- 核销类专属:退款截止时间;过期未核销的处理方式由商户配置(§6.3.6 R7)
    refund_deadline   TIMESTAMPTZ,
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE mall.mall_order (
    id            BIGSERIAL PRIMARY KEY,
    product_id    BIGINT       NOT NULL,
    buyer_user_id BIGINT       NOT NULL,
    amount_cents  BIGINT       NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'CREATED',
    -- 核销码:一次性,含防伪签名,核销后立即失效(§6.3.6 R5)
    voucher_code  VARCHAR(100) UNIQUE,
    redeemed_at   TIMESTAMPTZ,
    refunded_at   TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

GRANT USAGE ON SCHEMA mall TO mall_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA mall TO mall_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA mall TO mall_user;
