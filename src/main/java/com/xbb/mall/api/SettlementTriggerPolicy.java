package com.xbb.mall.api;

/**
 * §6.3.7 结算触发策略,挂入 §8 的策略插件体系。
 * 与 GuaranteePolicy/CommissionPolicy 同属"策略只做决策、核心域只认事件"的设计语言。
 */
public interface SettlementTriggerPolicy {

    record OrderContext(long orderId, long productId, ProductSettlementMode mode) { }

    SettlementTrigger decide(OrderContext ctx);
}
