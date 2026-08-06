-- 服务站可以是公司,也可以是个人(老板 2026-08-06)。
--
-- 原来 credit_code 是 NOT NULL UNIQUE —— **个人根本注册不进来**,
-- 他没有统一社会信用代码。绕过去的做法是让人随便填一个,
-- 那会污染唯一索引,而且事后分不清哪个是真的。
--
-- 同时补上地址:organization 表压根没有地址字段,而求职端岗位卡片
-- 要显示"在哪上班"。缺它的时候界面只能显示空白。

ALTER TABLE org.organization
    ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'COMPANY',
    -- 对外展示的地址。个人服务站填经营地址,企业填注册或办公地址
    ADD COLUMN address VARCHAR(200);

ALTER TABLE org.organization ALTER COLUMN credit_code DROP NOT NULL;

-- 列上的 UNIQUE 会拒绝多行 NULL 吗?不会,Postgres 允许多个 NULL。
-- 但列级唯一约束会生成一个普通唯一索引,而我们想表达的是
-- "有代码的才要求唯一" —— 换成部分唯一索引,意图直接写在索引上
ALTER TABLE org.organization DROP CONSTRAINT IF EXISTS organization_credit_code_key;
CREATE UNIQUE INDEX organization_credit_code_idx
    ON org.organization (credit_code) WHERE credit_code IS NOT NULL;

ALTER TABLE org.organization
    ADD CONSTRAINT organization_subject_type_ck
        CHECK (subject_type IN ('COMPANY', 'INDIVIDUAL')),

    -- **公司必须有代码,个人必须没有。**
    -- 只写"个人可以为空"的话,个人主体上填一个代码也能过 ——
    -- 于是同一个概念有两种表示,取数的地方迟早漏判一种
    ADD CONSTRAINT organization_credit_code_ck CHECK (
        (subject_type = 'COMPANY'    AND credit_code IS NOT NULL) OR
        (subject_type = 'INDIVIDUAL' AND credit_code IS NULL)),

    -- 个人主体只能是服务站。招人的企业和工厂必须是公司 ——
    -- 用工主体是个人的话,劳务合同、完税凭证、保证金全都没有落脚点
    ADD CONSTRAINT organization_individual_only_station_ck CHECK (
        subject_type = 'COMPANY' OR type = 'SERVICE_STATION'),

    -- 个人服务站**必须有那个人**。V5 允许服务站暂时没有站长
    -- (平台先规划点位、再派人),但"个人主体"指的就是那个人,
    -- 没有人的个人服务站不知道在说谁
    ADD CONSTRAINT organization_individual_needs_person_ck CHECK (
        subject_type = 'COMPANY' OR legal_rep_user_id IS NOT NULL);

-- 一个人只能有一个个人服务站。允许多个的话,同一个人名下几个站,
-- 佣金归属和结算主体全都对不上
CREATE UNIQUE INDEX organization_individual_person_idx
    ON org.organization (legal_rep_user_id)
    WHERE subject_type = 'INDIVIDUAL';
