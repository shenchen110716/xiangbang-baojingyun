package com.xbb.mall.api;

import java.time.Instant;
import java.util.Optional;

public interface MallApi {

    enum OrderStatus { CREATED, PAID, REDEEMED, REFUNDED }

    record ProductView(long id, long merchantId, String title, ProductSettlementMode settlementMode,
                        long priceCents, String sessionLabel, int stock) { }

    record OrderView(long id, long productId, long buyerUserId, long amountCents,
                      OrderStatus status, String voucherCode) { }

    long publishProduct(long merchantId, String title, ProductSettlementMode mode,
                         long priceCents, String sessionLabel, int stock, Instant refundDeadline);

    /** 下单锁库存(§6.3.6 R1 防超卖)。 */
    long placeOrder(long productId, long buyerUserId);

    /** 支付成功。即时结算商品在这一步触发结算;核销结算商品不触发。 */
    void pay(long orderId);

    /** 到场核销(§6.3.5)。核销成功才触发核销结算商品的结算。 */
    void redeem(String voucherCode);

    /** 退款(§6.3.6 R6 按截止时间分段)。 */
    void refund(long orderId, long buyerUserId);

    Optional<ProductView> findProduct(long productId);

    Optional<OrderView> findOrder(long orderId);
}
