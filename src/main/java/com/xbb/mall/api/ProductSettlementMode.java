package com.xbb.mall.api;

/** §6.3.7:商户上架时选择,策略据此决定结算触发时机。 */
public enum ProductSettlementMode {
    /** 即时结算:支付成功即触发结算与计佣,概不退货(仅 5 分钟误触发撤销窗口) */
    INSTANT,
    /** 核销结算:核销成功才触发,核销前按截止时间规则可退 */
    REDEEM_GATED
}
