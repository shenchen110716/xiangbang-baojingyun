-- 服务站分成比例改为**按业务类目分别设**(岗位 / 商品 / 培训 …)。
--
-- 改动前:broker.station.station_percent 是**一个数**,一个站只有一个比例。
-- 但岗位、商品、培训的毛利结构完全不同,用同一个比例要么让服务站在商品上亏,
-- 要么让平台在岗位上亏 —— 而这件事只有等对账才看得出来。
--
-- **类目用字符串而不是枚举。**培训域现在还不存在,以后还会有别的类目;
-- 做成枚举的话每加一个类目都要改代码、发一次版,而这本该是后台配一下的事。
-- 可选值由 ops 字典提供给界面做下拉,不在这里写死。

CREATE TABLE broker.station_rate (
    id                BIGSERIAL PRIMARY KEY,
    -- **NULL = 平台默认**。这样"平台默认"和"某站的覆盖"是同一张表、同一套取数规则,
    -- 不用在代码里维护两条互不相干的路径(那种地方最容易出现两边不一致)
    station_org_id    BIGINT,
    category          VARCHAR(32) NOT NULL,
    percent           INT         NOT NULL,
    updated_by        BIGINT      NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT station_rate_percent_range CHECK (percent >= 0 AND percent <= 100)
);

-- 同一个站的同一个类目只能有一条。**平台默认那条(station_org_id IS NULL)也一样**,
-- 但唯一索引对 NULL 不生效,所以要分成两条部分索引
CREATE UNIQUE INDEX station_rate_station_category_idx
    ON broker.station_rate (station_org_id, category)
    WHERE station_org_id IS NOT NULL;

CREATE UNIQUE INDEX station_rate_default_category_idx
    ON broker.station_rate (category)
    WHERE station_org_id IS NULL;

-- 把已有的单一比例迁进来,记成"岗位"类目 ——
-- 现有的佣金流水全部来自岗位结算,所以这个对应是准的。
-- 不迁的话,已经设过比例的服务站会在上线瞬间静默回退到平台默认
INSERT INTO broker.station_rate (station_org_id, category, percent, updated_by)
SELECT org_id, 'JOB', station_percent, 0
FROM broker.station
WHERE station_percent IS NOT NULL;

-- 变更留痕。这是在改钱怎么分,事后要查得到是谁改的
CREATE TABLE broker.station_rate_change (
    id              BIGSERIAL PRIMARY KEY,
    station_org_id  BIGINT,
    category        VARCHAR(32)  NOT NULL,
    old_percent     INT,
    new_percent     INT          NOT NULL,
    changed_by      BIGINT       NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX station_rate_change_idx ON broker.station_rate_change (station_org_id, id DESC);

-- 铁律 1:新表要显式授权
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.station_rate TO broker_user;
GRANT SELECT, INSERT ON broker.station_rate_change TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.station_rate_id_seq TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.station_rate_change_id_seq TO broker_user;
