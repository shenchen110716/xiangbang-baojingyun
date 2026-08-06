-- 总价模式的佣金比例,按**类目 + 地区**配(老板 2026-08-06)。
--
--   佣金总额     = 总价 × commission_pct
--   派遣公司留存 = 佣金总额 × dispatch_retain_pct   ← 第三方持证派遣主体,独立收款方
--   服务站佣金总额 = 佣金总额 − 派遣公司留存         ← 再走 commission_scheme 那四档
--
-- **和 commission_scheme 是上下两层,不是两套并行的东西。**
-- 这一层决定"总价里有多少钱进佣金池、派遣公司先拿走多少";
-- 下一层决定"剩下的池子在业务员/上级/平台/服务站之间怎么分"。
-- 挤进同一张表的话,一行里既有"对总价的比例"又有"对剩余的比例",
-- 两种基数混在一起,迟早有人拿错基数去算。

CREATE TABLE broker.commission_rate (
    id                  BIGSERIAL PRIMARY KEY,
    category            VARCHAR(32) NOT NULL,
    -- 国标行政区划代码(GB/T 2260)。**NULL = 全国兜底。**
    -- 取数从细到粗:区县(6 位) → 市(前 4+00) → 省(前 2+0000) → 全国。
    -- 用国标码是因为层级就编码在数字里 —— 换成"华东区"这种自定义分组的话,
    -- 每加一个地区都要维护一张归属表,那张表和实际区划对不上就分错钱。
    --
    -- **用 VARCHAR 不用 CHAR。**CHAR 会拿空格补齐,'320506' 和 '320506 '
    -- 在有些比较里相等、有些里不等 —— 而这一列是拿来做精确匹配的
    region_code         VARCHAR(6),

    -- 总价里有多少算佣金
    commission_pct      INT NOT NULL,
    -- 佣金里派遣公司留多少
    dispatch_retain_pct INT NOT NULL DEFAULT 0,
    -- 收这笔留存的派遣公司。**不校验它是不是派遣主体** ——
    -- 那要跨域读组织表,是铁律 1 明令禁止的;类型校验放在服务层经 org 的 api 查
    dispatch_org_id     BIGINT,

    updated_by          BIGINT      NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT rate_pct_range CHECK (
        commission_pct BETWEEN 0 AND 100 AND dispatch_retain_pct BETWEEN 0 AND 100),

    -- **留了钱就得有人收。**没指定派遣公司却留了比例的话,
    -- 那笔钱从佣金池里扣掉了、却挂不到任何收款方 —— 对账时是一个凭空消失的窟窿,
    -- 而且要等月底才看得见
    CONSTRAINT rate_retain_needs_payee CHECK (
        dispatch_retain_pct = 0 OR dispatch_org_id IS NOT NULL)
);

-- 同一个(类目, 地区)只能有一条。分成两条的话取数要靠排序决定用哪条,
-- 而排序是隐式的 —— 换个数据库版本结果就可能变
CREATE UNIQUE INDEX rate_category_region_idx
    ON broker.commission_rate (category, region_code)
    WHERE region_code IS NOT NULL;

CREATE UNIQUE INDEX rate_category_nationwide_idx
    ON broker.commission_rate (category)
    WHERE region_code IS NULL;

-- 变更留痕。这是在改钱怎么算,事后要查得到是谁改的、改前是什么
CREATE TABLE broker.commission_rate_change (
    id          BIGSERIAL PRIMARY KEY,
    category    VARCHAR(32)  NOT NULL,
    region_code VARCHAR(6),
    old_value   TEXT,
    new_value   TEXT         NOT NULL,
    changed_by  BIGINT       NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX rate_change_idx ON broker.commission_rate_change (category, id DESC);

-- **不种任何默认值。**种一个"全国 10%"进去的话,总价岗位一上线就按那个比例扣钱,
-- 而那个数字是我编的。没有配置时让它明确报错,由后台先配。
-- (commission_scheme 那次种了默认值,因为那是把已有行为原样搬过来;这里是全新的口径。)

GRANT SELECT, INSERT, UPDATE, DELETE ON broker.commission_rate TO broker_user;
GRANT SELECT, INSERT ON broker.commission_rate_change TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.commission_rate_id_seq TO broker_user;
GRANT USAGE, SELECT ON SEQUENCE broker.commission_rate_change_id_seq TO broker_user;
