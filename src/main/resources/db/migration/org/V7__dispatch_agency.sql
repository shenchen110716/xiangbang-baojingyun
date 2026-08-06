-- 派遣公司:第三方持证劳务派遣主体(老板 2026-08-06 确认是**独立收款方**,不是平台自己)。
--
-- 它和「平台」那一档是两回事:平台那档在服务站佣金池里分,
-- 派遣留存则是在池子形成之前先从佣金总额里扣走的。
-- 当成同一个来做的话,两笔钱会记到同一个收款方名下,而它们要开给不同的主体。

-- 劳务派遣经营许可证编号。**只有派遣主体需要**,别的类型为空。
-- 不校验格式:各地编号规则不同,写死一个正则迟早拦掉合法的证
ALTER TABLE org.organization ADD COLUMN dispatch_license_no VARCHAR(64);

-- 派遣主体必须持证。**没证的公司做劳务派遣是违法的**,
-- 而且这笔留存要开发票,查得到证号才说得清钱付给了谁
ALTER TABLE org.organization
    ADD CONSTRAINT organization_dispatch_license_ck CHECK (
        type <> 'DISPATCH_AGENCY' OR dispatch_license_no IS NOT NULL);

-- 派遣主体只能是公司。个人不可能持有劳务派遣经营许可证
ALTER TABLE org.organization
    ADD CONSTRAINT organization_dispatch_is_company_ck CHECK (
        type <> 'DISPATCH_AGENCY' OR subject_type = 'COMPANY');

-- 证号唯一。同一张证挂在两家名下,只可能是填错或者冒用
CREATE UNIQUE INDEX organization_dispatch_license_idx
    ON org.organization (dispatch_license_no)
    WHERE dispatch_license_no IS NOT NULL;
