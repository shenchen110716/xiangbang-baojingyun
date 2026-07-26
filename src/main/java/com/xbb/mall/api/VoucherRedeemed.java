package com.xbb.mall.api;

import java.time.Instant;

/** §9.2:核销结算路径的**前置事件**,由商城域内部转化为 OrderSettlementTriggered。 */
public record VoucherRedeemed(long orderId, long productId, long merchantId, long buyerUserId,
                               long amountCents, Instant occurredAt) { }
