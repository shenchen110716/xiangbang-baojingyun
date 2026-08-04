-- 平台参数中心。
--
-- 此前这些值全部写死在各域的 `static final` 常量里,改一个要重新发版;
-- 而它们是**运营参数**(佣金比例、信用分权重、薪资合理区间、降级天数),
-- 本来就该由平台在界面上调。
--
-- 为什么不用已有的 dictionary_item:字典是"受控词表"(一个 type 下多个词条),
-- 参数是"一个键一个值"。硬塞进字典要靠约定区分,而约定不会被机器检查。
-- 老系统那边就是塞在一张 sys_config 里再 `limit 1` 取任意一行——多行时取哪行不确定。

CREATE TABLE ops.platform_setting (
    setting_key   VARCHAR(80)  PRIMARY KEY,
    value         VARCHAR(200) NOT NULL,
    value_type    VARCHAR(16)  NOT NULL,   -- INT / DECIMAL / BOOLEAN / STRING
    category      VARCHAR(32)  NOT NULL,   -- 界面分组
    label         VARCHAR(60)  NOT NULL,   -- 中文名
    description   VARCHAR(300),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    BIGINT,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT platform_setting_type_ck
        CHECK (value_type IN ('INT', 'DECIMAL', 'BOOLEAN', 'STRING'))
);

-- 改动留痕。这些参数直接决定分给谁多少钱,"谁在什么时候把佣金从 60% 改成 40%"
-- 事后必须查得到。老系统的 sys_config 改了就是改了,没有任何痕迹。
CREATE TABLE ops.platform_setting_change (
    id           BIGSERIAL PRIMARY KEY,
    setting_key  VARCHAR(80)  NOT NULL,
    old_value    VARCHAR(200),
    new_value    VARCHAR(200) NOT NULL,
    changed_by   BIGINT       NOT NULL,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reason       VARCHAR(200)
);
CREATE INDEX platform_setting_change_key_idx
    ON ops.platform_setting_change (setting_key, changed_at DESC);

-- ── 种子数据:全部来自 2026-08-04 的审计,值与当前硬编码常量一致 ──
-- 一致很重要:上线这一版**不能改变任何现有行为**,只是把值搬到可改的地方。
-- 行为变更应该是运营在界面上做的决定,不是发版时悄悄带的。

INSERT INTO ops.platform_setting (setting_key, value, value_type, category, label, description) VALUES
-- 经纪人 / 业务员
('broker.demotion.days',              '90',     'INT',     'BROKER',   '业务员降级天数',
 '连续多少天无活跃则降级。有下级的被架空(下级上提)并重置活跃时间,无下级的标记为已降级。根业务员不受影响'),

-- 佣金分成(照搬老系统的六档模型,基数 = 浮动佣金 × 考勤系数)
('broker.commission.active.percent',        '60',  'INT', 'COMMISSION', '主动佣金比例(%)',
 '直接邀请人拿走的比例,从浮动佣金总额中先扣'),
('broker.commission.platform.percent',      '20',  'INT', 'COMMISSION', '平台佣金比例(%)',
 '在扣掉主动佣金后的剩余部分中,平台占的比例'),
('broker.commission.passive.percent',       '30',  'INT', 'COMMISSION', '被动佣金比例(%)',
 '在扣掉主动佣金后的剩余部分中,进入被动分配池的比例'),
('broker.commission.passive.step.percent',  '30',  'INT', 'COMMISSION', '逐级被动分配比例(%)',
 '被动池沿邀请链向下时,每一级拿走当前剩余的比例。越往下越少'),
('broker.commission.station.percent',       '50',  'INT', 'COMMISSION', '服务站佣金比例(%)',
 '在扣掉主动佣金后的剩余部分中,服务站占的比例'),
('broker.commission.min.payout.cents',      '100', 'INT', 'COMMISSION', '被动分配下限(分)',
 '被动池余额低于此值就停止继续向下分配,避免产生大量一分钱的流水'),

-- 信用分
('credit.new.user.score',      '60',   'DECIMAL', 'CREDIT', '新用户初始信用分', NULL),
('credit.half.life.days',      '90',   'DECIMAL', 'CREDIT', '信用分半衰期(天)', '历史评价影响随时间衰减的半衰期'),
('credit.penalty.k',           '0.7',  'DECIMAL', 'CREDIT', '惩罚系数',         NULL),
('credit.weight.fulfillment',  '0.5',  'DECIMAL', 'CREDIT', '履约权重',         '三项权重之和应为 1'),
('credit.weight.review',       '0.3',  'DECIMAL', 'CREDIT', '评价权重',         '三项权重之和应为 1'),
('credit.weight.penalty',      '0.2',  'DECIMAL', 'CREDIT', '惩罚权重',         '三项权重之和应为 1'),
('credit.recent.days',         '90',   'INT',     'CREDIT', '近期窗口(天)',     NULL),

-- 薪资合理性(只质疑不拦截)
('wage.min.cents',           '5000',   'INT',     'WAGE', '日薪下限(分)',  '低于此值发岗时会被反问,但不阻止发布'),
('wage.max.cents',           '500000', 'INT',     'WAGE', '日薪上限(分)',  '高于此值发岗时会被反问,但不阻止发布'),
('wage.deviation.multiple',  '3.0',    'DECIMAL', 'WAGE', '偏离倍数',      '偏离同组织历史均值超过此倍数时反问'),

-- 保证金
('deposit.full.rate', '0.5', 'DECIMAL', 'DEPOSIT', '押金比例', NULL),

-- 匹配
('matching.distance.decay.km', '5.0', 'DECIMAL', 'MATCHING', '距离衰减(km)', '距离每超过此值,匹配得分衰减一档'),
('matching.epsilon',           '0.2', 'DECIMAL', 'MATCHING', '探索率',       '推荐结果中随机探索的比例,避免只推头部'),

-- 语音发单
('voice.min.confidence', '0.7', 'DECIMAL', 'VOICE', '意图最低置信度', '低于此值不自动建单,转人工确认');

-- 新建的表必须自己授权:`GRANT ON ALL TABLES` 只对执行那一刻已存在的表生效,
-- V1 里那句管不到后来加的表。漏了的话域用户读自己的表都会 permission denied
-- —— 这次就是被铁律 1 的隔离在测试阶段抓出来的,不然会一路带到线上。
GRANT SELECT, INSERT, UPDATE, DELETE ON ops.platform_setting        TO ops_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ops.platform_setting_change TO ops_user;
GRANT USAGE, SELECT ON SEQUENCE ops.platform_setting_change_id_seq  TO ops_user;
