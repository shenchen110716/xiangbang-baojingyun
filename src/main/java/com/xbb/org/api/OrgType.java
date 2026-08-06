package com.xbb.org.api;

/**
 * 组织主体类型。
 *
 * <p>放在 api 而不是 internal:它出现在 {@link OrganizationApproved} 事件里,
 * 消费方要用它判断"这是不是服务站"。留在 internal 的话,订阅事件就等于引用别人的内部类,
 * ModularityTests 会直接拦下——这一条就是被它抓出来的。
 */
public enum OrgType {
    ENTERPRISE,
    FACTORY,
    SERVICE_STATION,
    /**
     * 第三方持证劳务派遣主体。<b>独立收款方,不是平台自己</b>(老板 2026-08-06)。
     *
     * <p>总价模式下它在佣金池形成**之前**先拿走留存那一段,
     * 和「平台」那一档(在池子里分)是两回事 —— 两笔钱要开给不同的主体。
     *
     * <p>加这一种是安全的:全代码库只有两处按类型分支,都是"是不是服务站"。
     */
    DISPATCH_AGENCY
}
