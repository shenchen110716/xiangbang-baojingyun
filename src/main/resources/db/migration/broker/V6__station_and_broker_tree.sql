-- 服务站与业务员网络。照搬老系统的模型(Broker: parBrokerId / stationId / lastActiveTime),
-- 两处刻意不照抄,理由写在下面。

-- ── 服务站 ──
-- 本域副本 + 本域自有的佣金属性。
-- 身份与入驻审核归组织域(type = SERVICE_STATION),这里只订阅"已通过审核"的事件;
-- 而"这个站抽几个点"是佣金的事,归经纪人域自己存 —— 组织域不该知道佣金。
CREATE TABLE broker.station (
    org_id            BIGINT PRIMARY KEY,
    name              VARCHAR(120) NOT NULL,
    legal_rep_user_id BIGINT       NOT NULL,
    -- 服务站佣金比例(%)。**为空表示用平台默认**(参数中心的 broker.commission.station.percent)。
    -- 用 NULL 而不是把默认值抄进来:抄进来之后平台改默认值,已建的站不会跟着变,
    -- 而"没单独设过"和"设成了和默认一样"就再也分不清了。
    station_percent   INT,
    approved_at       TIMESTAMPTZ  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT station_percent_range_ck
        CHECK (station_percent IS NULL OR (station_percent >= 0 AND station_percent <= 100))
);

-- ── 业务员(经纪人)网络 ──
ALTER TABLE broker.broker
    -- 所属服务站。为空 = 尚未挂靠任何服务站
    ADD COLUMN station_org_id BIGINT,
    -- 上级业务员。**为空 = 根业务员,永不降级**
    -- (老系统用 parBrokerId = 0 表示根,这里用 NULL —— 0 是个合法的 user_id,
    --  拿它当哨兵值迟早撞上)
    ADD COLUMN parent_user_id BIGINT,
    ADD COLUMN last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ACTIVE / DEMOTED。**不物理删除** —— 这是第一处不照抄:
    -- 老系统对没有下级的业务员直接 delete,而 M10 设计文档自己要求"归属变更全程留痕"。
    -- 更要命的是删掉之后他名下已产生的佣金归属就断了,出纠纷查不回来。
    ADD COLUMN status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN version        BIGINT      NOT NULL DEFAULT 0;

ALTER TABLE broker.broker
    ADD CONSTRAINT broker_status_ck CHECK (status IN ('ACTIVE', 'DEMOTED'));

-- 自己不能是自己的上级。防不住多层环(A→B→A),那个由服务层查链路时挡。
ALTER TABLE broker.broker
    ADD CONSTRAINT broker_not_own_parent_ck CHECK (parent_user_id IS NULL OR parent_user_id <> user_id);

CREATE INDEX broker_parent_idx  ON broker.broker (parent_user_id) WHERE parent_user_id IS NOT NULL;
CREATE INDEX broker_station_idx ON broker.broker (station_org_id) WHERE station_org_id IS NOT NULL;
-- 降级任务按"活跃时间早于阈值且非根"扫,这个联合索引正好覆盖
CREATE INDEX broker_demotion_idx ON broker.broker (last_active_at) WHERE parent_user_id IS NOT NULL;

-- ── 变更留痕 ──
-- M10 文档要求"归属发生变更时全程留痕,可追溯操作人与前后值"。
-- 老系统只有 ChangeBrokerLog 的表结构描述,降级路径上完全没有写。
CREATE TABLE broker.broker_change_log (
    id           BIGSERIAL PRIMARY KEY,
    broker_user_id BIGINT     NOT NULL,
    change_type  VARCHAR(24)  NOT NULL,   -- STATION / PARENT / STATUS
    old_value    VARCHAR(64),
    new_value    VARCHAR(64),
    -- 系统自动降级时没有操作人,记 NULL 而不是编一个 0
    changed_by   BIGINT,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason       VARCHAR(200)
);
CREATE INDEX broker_change_log_broker_idx
    ON broker.broker_change_log (broker_user_id, changed_at DESC);

-- 新表要自己授权:GRANT ON ALL TABLES 只对执行那一刻已存在的表生效。
-- 上一个迁移就漏了这个,被铁律 1 的隔离在测试阶段抓住。
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.station          TO broker_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON broker.broker_change_log TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.broker_change_log_id_seq  TO broker_user;
