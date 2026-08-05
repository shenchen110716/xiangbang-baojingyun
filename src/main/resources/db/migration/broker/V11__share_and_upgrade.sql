-- 分享归因与业务员自动升级。
--
-- 员工把岗位或商品分享给别人,对方经此报名/成交后,分享人自动升级为业务员。
-- 这是老系统 M10「多级裂变」的入口:原先只能由已是经纪人的人去绑定工人,
-- 普通员工没有任何变成业务员的路径。

CREATE TABLE broker.share (
    id                BIGSERIAL PRIMARY KEY,
    sharer_user_id    BIGINT      NOT NULL,
    -- JOB / PRODUCT / TRAINING …与分成比例的类目同一套口径
    target_type       VARCHAR(32) NOT NULL,
    target_id         BIGINT      NOT NULL,
    -- 分享码。对方带着它来报名/下单,归因就落在这条分享上
    code              VARCHAR(32) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT share_code_unique UNIQUE (code)
);

-- 同一个人重复分享同一个东西,复用同一条记录(下面的服务层保证),
-- 这个索引让那次查找走索引而不是全表
CREATE INDEX share_sharer_target_idx ON broker.share (sharer_user_id, target_type, target_id);

CREATE TABLE broker.share_conversion (
    id                BIGSERIAL PRIMARY KEY,
    share_id          BIGINT      NOT NULL REFERENCES broker.share (id),
    converted_user_id BIGINT      NOT NULL,
    -- PENDING = 已通过分享进来但还没达成计数条件;COUNTED = 已计入升级门槛
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- 计入时对应的业务单据(报名单或订单),用于对账
    reference_id      BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    counted_at        TIMESTAMPTZ,

    CONSTRAINT share_conversion_status_valid CHECK (status IN ('PENDING', 'COUNTED'))
);

-- **一个人只能被归因给一个分享人。**老系统那条"归属唯一"
-- (一个被邀请人对应一条有效邀请归属)。少了它,两个人分享给同一个人时
-- 两边都算业绩,佣金会被重复计算,而且谁也说不清该算谁的
CREATE UNIQUE INDEX share_conversion_one_per_user_idx
    ON broker.share_conversion (converted_user_id);

CREATE INDEX share_conversion_share_idx ON broker.share_conversion (share_id, status);

-- 业务员来源留痕:自动升级来的、站长授权的、还是自助注册的。
-- 事后要说得清一个人凭什么是业务员 —— 尤其自动升级那条,是系统替他做的决定
CREATE TABLE broker.broker_origin (
    user_id       BIGINT      PRIMARY KEY,
    origin        VARCHAR(20) NOT NULL,
    -- 自动升级时:触发升级的那条归因;站长授权时:授权人
    source_ref    BIGINT,
    granted_by    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT broker_origin_valid CHECK (origin IN ('SELF', 'AUTO_UPGRADE', 'STATION_GRANT'))
);

-- 铁律 1:新表要显式授权
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.share TO broker_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.share_conversion TO broker_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.broker_origin TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.share_id_seq TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.share_conversion_id_seq TO broker_user;
