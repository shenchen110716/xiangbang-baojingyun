package com.xbb.fund.api;

/**
 * 监管账户分型(§6.4.2 资金隔离)。
 * "平台自有收入与用户资金**分账,不混同**"——所以是不同账户各自记余额,
 * 不是一个大池子加个标记字段。
 */
public enum AccountType {
    /** 用工企业打进来的、待发给工人的在途资金 */
    USER_FUNDS,
    /** 平台服务费收入 */
    PLATFORM_REVENUE,
    /** 押金/担保资金池 */
    GUARANTEE_POOL
}
