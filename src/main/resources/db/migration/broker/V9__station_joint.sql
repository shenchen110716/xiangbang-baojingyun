-- 服务站间联合(老系统 M10 §3.4 StationJoint)。
--
-- 老系统的流程:A 站发起申请并带上分成比例 rate → B 站确认 → 两站联合;
-- 之后归集到 A 站的佣金,按 rate 分给 B 站。可取消(未确认时)、可解除(已联合后)。
--
-- **为什么不做成"服务站有个 parent"。**联合是**对等**关系,不是上下级:
-- 两个站互相引流,谁发起只决定谁付这笔分成。做成树的话,
-- 一个站想同时和两家联合就表达不了,而那正是这个功能的常见用法。

CREATE TABLE broker.station_joint (
    id                  BIGSERIAL PRIMARY KEY,
    -- 发起方。**佣金从这一方的份额里切给对方**
    from_org_id         BIGINT      NOT NULL,
    to_org_id           BIGINT      NOT NULL,
    -- 分成比例(%),从发起方的服务站佣金里切多少给对方
    rate_percent        INT         NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    applied_by          BIGINT      NOT NULL,
    confirmed_by        BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,

    -- **自己不能和自己联合。**放进来的话分账会把钱切给自己再加回来,
    -- 金额看着对,但流水里凭空多出两条互相抵消的记录,对账时没人看得懂
    CONSTRAINT joint_not_self CHECK (from_org_id <> to_org_id),
    -- 0% 的联合没有业务含义;100% 意味着发起方一分不留,那多半是填错了
    CONSTRAINT joint_rate_range CHECK (rate_percent > 0 AND rate_percent < 100),
    CONSTRAINT joint_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED', 'ENDED'))
);

-- **同一对服务站同时只能有一条未结束的联合。**
-- 老系统靠"已申请则拦截重复"在应用层判,那在并发下无效(两边同时查到"没有"然后都插入)。
-- 交给数据库:PENDING 和 ACTIVE 都算未结束,方向敏感(A→B 和 B→A 是两笔不同的分成)
CREATE UNIQUE INDEX station_joint_active_pair_idx
    ON broker.station_joint (from_org_id, to_org_id)
    WHERE status IN ('PENDING', 'ACTIVE');

-- 分账时按发起方查"我要分给谁",是主路径
CREATE INDEX station_joint_from_status_idx ON broker.station_joint (from_org_id, status);
CREATE INDEX station_joint_to_status_idx   ON broker.station_joint (to_org_id, status);

-- 铁律 1:新表要显式授权。GRANT ON ALL TABLES 只覆盖执行那一刻已存在的表
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.station_joint TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.station_joint_id_seq TO broker_user;

-- 佣金表要能存下"联合分走的那一档"。
--
-- **不复用 STATION。**两者的钱来源不同:STATION 是归集站自己挣的,
-- JOINT 是从归集站的份额里切给联合方的。混在一起的话,服务站看自己的佣金明细
-- 会发现数字对不上,却查不出为什么。
ALTER TABLE broker.commission DROP CONSTRAINT commission_tier_ck;
ALTER TABLE broker.commission
    ADD CONSTRAINT commission_tier_ck CHECK (tier IN ('ACTIVE', 'PASSIVE', 'STATION', 'JOINT'));

-- 归属约束同步放宽:JOINT 和 STATION 一样是发给组织的,没有经纪人
ALTER TABLE broker.commission DROP CONSTRAINT commission_payee_ck;
ALTER TABLE broker.commission
    ADD CONSTRAINT commission_payee_ck CHECK (
        (tier IN ('STATION', 'JOINT') AND station_org_id IS NOT NULL AND broker_user_id IS NULL)
     OR (tier NOT IN ('STATION', 'JOINT') AND station_org_id IS NULL AND broker_user_id IS NOT NULL));
