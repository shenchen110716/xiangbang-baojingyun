package com.xbb.org.api;

/**
 * 组织主体类型。
 *
 * <p>放在 api 而不是 internal:它出现在 {@link OrganizationApproved} 事件里,
 * 消费方要用它判断"这是不是服务站"。留在 internal 的话,订阅事件就等于引用别人的内部类,
 * ModularityTests 会直接拦下——这一条就是被它抓出来的。
 */
public enum OrgType { ENTERPRISE, FACTORY, SERVICE_STATION }
