package com.xbb.mall.internal;

import com.xbb.mall.api.ProductSettlementMode;
import com.xbb.mall.api.SettlementTrigger;
import com.xbb.mall.api.SettlementTriggerPolicy;
import org.springframework.stereotype.Component;

/** 纯函数:按商品结算模式决定触发时机(§6.3.7)。 */
@Component
public class ModeBasedSettlementTriggerPolicy implements SettlementTriggerPolicy {

    @Override
    public SettlementTrigger decide(OrderContext ctx) {
        return ctx.mode() == ProductSettlementMode.INSTANT
                ? SettlementTrigger.ON_PAYMENT
                : SettlementTrigger.ON_REDEMPTION;
    }
}
