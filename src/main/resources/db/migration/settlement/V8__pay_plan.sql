-- 计薪方案。对应老系统的 JobPayPlan + JobPlanFactor + JobPlanSnapshot。
--
-- **不做单独的快照表。** 老系统用 planSnapshotId 存快照,那意味着同一份方案有两处副本,
-- 迟早对不上。这里改成:**方案版本不可变** —— 改方案就是发一个新版本、旧版本置为失效,
-- 工资单直接引用它当时用的那个版本号。少一张表,而且"快照和方案不一致"这种状态不存在。

CREATE TABLE settlement.pay_plan (
    id                 BIGSERIAL PRIMARY KEY,
    job_id             BIGINT       NOT NULL,
    -- 同一岗位下递增。工资单记的就是这个 id,所以它必须永不被改写
    version            INT          NOT NULL,
    name               VARCHAR(60)  NOT NULL,

    -- HOURLY 按小时 / DAILY 按天 / MONTHLY 按月 / PIECE 按件
    pay_type           VARCHAR(16)  NOT NULL,
    -- 基本工资:按 pay_type 的单位计(时薪/日薪/月薪)
    basic_salary_cents BIGINT       NOT NULL DEFAULT 0,
    -- **浮动工资。这是佣金分账的基数** —— 老系统 JobComputerService 就是拿 floatSalary 算佣金的。
    -- 目前佣金用的是发放总额,等这块接上要改过去(记在 DESIGN 的已知缺口里)
    float_salary_cents BIGINT       NOT NULL DEFAULT 0,
    -- 固定工资:整期一笔,不按单位乘
    fixed_salary_cents BIGINT       NOT NULL DEFAULT 0,

    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    effective_from     DATE         NOT NULL,
    effective_to       DATE,
    created_by         BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pay_plan_version_uk   UNIQUE (job_id, version),
    CONSTRAINT pay_plan_type_ck      CHECK (pay_type IN ('HOURLY', 'DAILY', 'MONTHLY', 'PIECE')),
    CONSTRAINT pay_plan_status_ck    CHECK (status IN ('ACTIVE', 'EXPIRED')),
    CONSTRAINT pay_plan_amount_ck    CHECK (basic_salary_cents >= 0
                                        AND float_salary_cents >= 0
                                        AND fixed_salary_cents >= 0),
    CONSTRAINT pay_plan_period_ck    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- **同一岗位同一时刻只能有一个生效方案。**
-- 少了这条,算薪时不知道该用哪个 —— 而"用错方案"和"算错工资"是同一件事,
-- 且两个方案各自都是合法的,查起来毫无线索。
CREATE UNIQUE INDEX pay_plan_one_active_idx ON settlement.pay_plan (job_id) WHERE status = 'ACTIVE';
CREATE INDEX pay_plan_job_idx ON settlement.pay_plan (job_id, version DESC);

-- 方案下的调整项:奖励 / 扣款 / 罚款。
-- 金额一律存正数,方向由 factor_type 决定 —— 让"负数的扣款"和"正数的扣款"
-- 两种写法并存,迟早有人加错符号。
CREATE TABLE settlement.pay_plan_factor (
    id           BIGSERIAL PRIMARY KEY,
    plan_id      BIGINT       NOT NULL REFERENCES settlement.pay_plan(id) ON DELETE CASCADE,
    factor_type  VARCHAR(16)  NOT NULL,
    name         VARCHAR(60)  NOT NULL,
    amount_cents BIGINT       NOT NULL,
    CONSTRAINT factor_type_ck   CHECK (factor_type IN ('BONUS', 'DEDUCTION', 'PENALTY')),
    CONSTRAINT factor_amount_ck CHECK (amount_cents > 0)
);
CREATE INDEX pay_plan_factor_idx ON settlement.pay_plan_factor (plan_id);

-- 工资单要记住"按哪个方案、按多少工时算出来的",否则事后无法解释金额怎么来的。
-- 老系统的工资单只有一个总额,出账疑问时只能靠人回忆。
ALTER TABLE settlement.settlement
    ADD COLUMN pay_plan_id BIGINT,
    ADD COLUMN minutes     INT NOT NULL DEFAULT 0,
    -- 明细快照(JSON):基本/浮动/固定/各调整项各多少。
    -- 存下来而不是每次重算:方案可以改版本,但已出的工资单必须永远解释得通。
    ADD COLUMN breakdown   TEXT;

GRANT SELECT, INSERT, UPDATE, DELETE ON settlement.pay_plan        TO settlement_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON settlement.pay_plan_factor TO settlement_user;
GRANT USAGE, SELECT ON SEQUENCE settlement.pay_plan_id_seq         TO settlement_user;
GRANT USAGE, SELECT ON SEQUENCE settlement.pay_plan_factor_id_seq  TO settlement_user;
