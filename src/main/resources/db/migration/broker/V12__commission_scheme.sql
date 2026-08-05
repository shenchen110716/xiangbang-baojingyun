-- 分配方案改为**按业务类目一整套**,不只是服务站那一档。
--
-- V10 只把「服务站比例」做成按类目可配,其余五档(主动/平台/被动/逐级/下限)
-- 仍然全局共用一套 —— 那是理解偏差。岗位、商品、培训的**整个分账结构**都不同:
-- 商品可能压根没有被动佣金,培训可能主动佣金极高。用同一套主动/被动比例去分,
-- 任何一个类目都是错的,而且错了只有对账才看得出来。

CREATE TABLE broker.commission_scheme (
    id                  BIGSERIAL PRIMARY KEY,
    -- NULL = 平台默认(对所有没单独设过的服务站生效)。
    -- 和 V10 一样让默认与覆盖同表同规则,取数只有一条路径 ——
    -- 分两处维护迟早两边不一致,而那要等对账才发现
    station_org_id      BIGINT,
    category            VARCHAR(32) NOT NULL,

    -- 主动佣金:直接经纪人从基数里拿走这个百分比
    active_pct          INT NOT NULL,
    -- 下面三档在**剩余**(基数 − 主动)里分,三者相加不能超过 100
    platform_pct        INT NOT NULL,
    passive_pct         INT NOT NULL,
    station_pct         INT NOT NULL,
    -- 被动逐级:每一级从被动池里拿走这个百分比,越往上越少
    passive_step_pct    INT NOT NULL,
    -- 单笔分账下限,低于它就不再往上分(否则会产生一串一分钱的流水,对账全是噪音)
    min_payout_cents    BIGINT NOT NULL,

    updated_by          BIGINT      NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT scheme_pct_range CHECK (
        active_pct BETWEEN 0 AND 100 AND platform_pct BETWEEN 0 AND 100
        AND passive_pct BETWEEN 0 AND 100 AND station_pct BETWEEN 0 AND 100
        AND passive_step_pct BETWEEN 0 AND 100),
    -- **平台 + 被动 + 服务站在同一块"剩余"里分。**加起来超过 100 就是凭空多分钱。
    -- 放数据库上是因为应用层那道校验漏一次没有任何症状
    CONSTRAINT scheme_remainder_sane CHECK (platform_pct + passive_pct + station_pct <= 100),
    CONSTRAINT scheme_min_payout_nonneg CHECK (min_payout_cents >= 0)
);

CREATE UNIQUE INDEX scheme_station_category_idx
    ON broker.commission_scheme (station_org_id, category)
    WHERE station_org_id IS NOT NULL;

CREATE UNIQUE INDEX scheme_default_category_idx
    ON broker.commission_scheme (category)
    WHERE station_org_id IS NULL;

-- **迁移的目标是行为不变。**
-- 五档取自参数中心当前的兜底值(和 FundEventListener 里写的一致),
-- 服务站那一档取 station_rate 里已有的平台默认,没有就用 50。
-- 不迁的话,上线瞬间分账会落到空表上。
INSERT INTO broker.commission_scheme
    (station_org_id, category, active_pct, platform_pct, passive_pct,
     station_pct, passive_step_pct, min_payout_cents, updated_by)
SELECT NULL, c.category, 60, 20, 30,
       COALESCE((SELECT percent FROM broker.station_rate
                 WHERE station_org_id IS NULL AND category = c.category), 50),
       30, 100, 0
FROM (VALUES ('JOB'), ('PRODUCT'), ('TRAINING')) AS c(category);

-- 服务站的覆盖:已单独设过服务站比例的保留那个值,其余档跟平台默认一致。
-- **只迁 station_pct** —— 别的档它本来就没单独设过,凭空给它一套是替老板做决定
INSERT INTO broker.commission_scheme
    (station_org_id, category, active_pct, platform_pct, passive_pct,
     station_pct, passive_step_pct, min_payout_cents, updated_by)
SELECT r.station_org_id, r.category, 60, 20, 30, r.percent, 30, 100, r.updated_by
FROM broker.station_rate r
WHERE r.station_org_id IS NOT NULL;

-- 变更留痕。这是在改钱怎么分,事后要查得到是谁改的、改前是什么
CREATE TABLE broker.commission_scheme_change (
    id              BIGSERIAL PRIMARY KEY,
    station_org_id  BIGINT,
    category        VARCHAR(32)  NOT NULL,
    old_value       TEXT,
    new_value       TEXT         NOT NULL,
    changed_by      BIGINT       NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX scheme_change_idx ON broker.commission_scheme_change (station_org_id, id DESC);

-- 铁律 1:新表要显式授权
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.commission_scheme TO broker_user;
GRANT SELECT, INSERT ON broker.commission_scheme_change TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.commission_scheme_id_seq TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.commission_scheme_change_id_seq TO broker_user;
