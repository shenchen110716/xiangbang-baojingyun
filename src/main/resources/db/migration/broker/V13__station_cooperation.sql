-- 服务站与用工单位的合作关系(老系统 M9「合作」ComApply)。
--
-- 老系统的流程:一方按编号搜索对方 → 申请合作 → **对方确认** → 建立关系
-- (可参与对方岗位/分成)→ 可解绑。和服务站间联合(V9)是同一套模式,
-- 所以这里刻意做成一样的形状:待确认 / 已生效 / 已撤回 / 已解除。
--
-- **为什么不复用 station_joint 那张表。**联合是站与站之间(对等,双方都是服务站),
-- 合作是站与用工单位之间(不对等,一方出岗位一方出人)。挤进同一张表要靠
-- "对方是什么类型"去分支,而那种分支迟早有人漏写一处 —— 分账正好是最不该漏的地方。

CREATE TABLE broker.station_cooperation (
    id                  BIGSERIAL PRIMARY KEY,
    station_org_id      BIGINT      NOT NULL,
    -- 用工单位:企业或工厂。**不校验类型** —— 服务站域只有服务站的副本,
    -- 判断对方是不是用工单位要跨域读表,那是铁律 1 明令禁止的。
    -- 类型校验放在发起那一步(服务层经 org 的 api 查),这里只存结果
    partner_org_id      BIGINT      NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- 谁发起的:服务站还是用工单位。决定谁能撤回
    initiated_by_station BOOLEAN    NOT NULL,
    applied_by          BIGINT      NOT NULL,
    confirmed_by        BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at        TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT coop_not_self CHECK (station_org_id <> partner_org_id),
    CONSTRAINT coop_status_valid CHECK (status IN ('PENDING', 'ACTIVE', 'CANCELLED', 'ENDED'))
);

-- **同一对(服务站,用工单位)同时只能有一条未结束的合作。**
-- 老系统靠"已申请则拦截重复"在应用层判,并发下两边都会查到"没有"然后都插入。
-- 交给数据库:PENDING 与 ACTIVE 都算未结束
CREATE UNIQUE INDEX coop_active_pair_idx
    ON broker.station_cooperation (station_org_id, partner_org_id)
    WHERE status IN ('PENDING', 'ACTIVE');

CREATE INDEX coop_station_idx ON broker.station_cooperation (station_org_id, status);
CREATE INDEX coop_partner_idx ON broker.station_cooperation (partner_org_id, status);

-- 操作员:合作关系建立之后,由服务站指派具体经办人。
--
-- **挂在合作关系上,不是挂在服务站上。**同一个服务站和三家企业合作,
-- 完全可能派三个不同的人去对接;挂在服务站上就表达不了这件事,
-- 而且撤销某一家的授权时会连带影响其它家。
CREATE TABLE broker.cooperation_operator (
    id              BIGSERIAL PRIMARY KEY,
    cooperation_id  BIGINT      NOT NULL REFERENCES broker.station_cooperation (id),
    user_id         BIGINT      NOT NULL,
    -- 解绑不删行:这个人经办过的事要能查到当时他是有授权的
    active          BOOLEAN     NOT NULL DEFAULT true,
    assigned_by     BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);

-- 同一份合作里同一个人只能有一条生效的授权
CREATE UNIQUE INDEX coop_operator_active_idx
    ON broker.cooperation_operator (cooperation_id, user_id)
    WHERE active;

CREATE INDEX coop_operator_user_idx ON broker.cooperation_operator (user_id) WHERE active;

-- 铁律 1:新表要显式授权
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.station_cooperation TO broker_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.cooperation_operator TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.station_cooperation_id_seq TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.cooperation_operator_id_seq TO broker_user;
