package com.xbb.mall.api;

import java.time.Instant;

/**
 * §6.3.7 + §9.2:即时结算与核销结算**殊途同归**汇入同一个事件。
 *
 * <p>"结算域**只订阅这一个事件**,不区分来源——差别只在触发时机,
 * 不需要结算域感知两套逻辑。"这与 §8"策略即接缝"是同一设计语言。
 */
public record OrderSettlementTriggered(long orderId, long productId, long merchantId, long buyerUserId,
                                        long amountCents, SettlementTrigger trigger, Instant occurredAt) { }
