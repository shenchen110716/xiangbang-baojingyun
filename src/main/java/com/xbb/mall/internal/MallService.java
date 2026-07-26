package com.xbb.mall.internal;

import com.xbb.mall.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
class MallService implements MallApi {

    private final ProductRepository products;
    private final MallOrderRepository orders;
    private final SettlementTriggerPolicy triggerPolicy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    MallService(ProductRepository products, MallOrderRepository orders,
                 SettlementTriggerPolicy triggerPolicy, ApplicationEventPublisher events,
                 Clock clock) {
        this.products = products;
        this.orders = orders;
        this.triggerPolicy = triggerPolicy;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional("mallTransactionManager")
    public long publishProduct(long merchantId, String title, ProductSettlementMode mode,
                                long priceCents, String sessionLabel, int stock, Instant refundDeadline) {
        return products.save(new Product(merchantId, title, mode, priceCents,
                sessionLabel, stock, refundDeadline)).getId();
    }

    /**
     * §6.3.6 R1 库存并发锁:读出商品(乐观锁版本号)→ 锁内复检剩余量 → 扣减。
     * 并发下两个请求抢最后一张票时,后提交的那个会因版本号冲突失败,不会超卖。
     */
    @Override
    @Transactional("mallTransactionManager")
    public long placeOrder(long productId, long buyerUserId) {
        Product product = products.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        product.reserveOne();
        products.save(product);
        return orders.save(new MallOrder(productId, buyerUserId, product.getPriceCents())).getId();
    }

    @Override
    @Transactional("mallTransactionManager")
    public void pay(long orderId) {
        MallOrder order = loadOrder(orderId);
        Product product = loadProduct(order.getProductId());

        order.markPaid(VoucherCode.generate(order.getId(), order.getProductId(), order.getBuyerUserId()));
        orders.save(order);

        // §6.3.7:两类商品殊途同归,只是触发时机不同
        SettlementTrigger trigger = triggerPolicy.decide(
                new SettlementTriggerPolicy.OrderContext(orderId, product.getId(), product.getSettlementMode()));
        if (trigger == SettlementTrigger.ON_PAYMENT) {
            publishSettlementTriggered(order, product, SettlementTrigger.ON_PAYMENT);
        }
    }

    @Override
    @Transactional("mallTransactionManager")
    public void redeem(String voucherCode) {
        MallOrder order = orders.findByVoucherCode(voucherCode)
                .orElseThrow(() -> new IllegalArgumentException("核销码无效"));
        Product product = loadProduct(order.getProductId());

        // 防伪校验:码里带签名,伪造的码即使猜中订单号也过不了
        if (!VoucherCode.verify(voucherCode, order.getId(), order.getProductId(), order.getBuyerUserId())) {
            throw new IllegalStateException("核销码签名校验失败");
        }
        order.redeem();
        orders.save(order);

        events.publishEvent(new VoucherRedeemed(order.getId(), product.getId(), product.getMerchantId(),
                order.getBuyerUserId(), order.getAmountCents(), clock.instant()));

        // §6.3.6 R4:核销结算商品的货款,核销成功才划给商户——
        // 防止"买了不来"占坑却让商户提前拿到全款
        if (product.getSettlementMode() == ProductSettlementMode.REDEEM_GATED) {
            publishSettlementTriggered(order, product, SettlementTrigger.ON_REDEMPTION);
        }
    }

    /** §6.3.6 R6:核销前 + 未过退款截止时间才可退;已核销或过期不可退。 */
    @Override
    @Transactional("mallTransactionManager")
    public void refund(long orderId, long buyerUserId) {
        MallOrder order = loadOrder(orderId);
        if (order.getBuyerUserId() != buyerUserId) {
            throw new IllegalStateException("只能退自己的订单");
        }
        Product product = loadProduct(order.getProductId());

        if (product.getSettlementMode() == ProductSettlementMode.INSTANT) {
            // §6.3.6 R3:即时结算商品概不退货(仅 5 分钟误触发撤销窗口,那是另一条路径)
            throw new IllegalStateException("该商品为即时结算商品,概不退货");
        }
        if (product.getRefundDeadline() != null && clock.instant().isAfter(product.getRefundDeadline())) {
            throw new IllegalStateException("已过退款截止时间,不能退款");
        }
        order.refund();
        orders.save(order);

        // 退款释放库存,票可以再卖给别人
        product.releaseOne();
        products.save(product);
    }

    private void publishSettlementTriggered(MallOrder order, Product product, SettlementTrigger trigger) {
        events.publishEvent(new OrderSettlementTriggered(order.getId(), product.getId(),
                product.getMerchantId(), order.getBuyerUserId(), order.getAmountCents(),
                trigger, clock.instant()));
    }

    @Override
    @Transactional(transactionManager = "mallTransactionManager", readOnly = true)
    public Optional<ProductView> findProduct(long productId) {
        return products.findById(productId).map(p -> new ProductView(p.getId(), p.getMerchantId(),
                p.getTitle(), p.getSettlementMode(), p.getPriceCents(), p.getSessionLabel(), p.getStock()));
    }

    @Override
    @Transactional(transactionManager = "mallTransactionManager", readOnly = true)
    public Optional<OrderView> findOrder(long orderId) {
        return orders.findById(orderId).map(o -> new OrderView(o.getId(), o.getProductId(),
                o.getBuyerUserId(), o.getAmountCents(), OrderStatus.valueOf(o.getStatus().name()),
                o.getVoucherCode()));
    }

    private MallOrder loadOrder(long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("订单不存在"));
    }

    private Product loadProduct(long productId) {
        return products.findById(productId).orElseThrow(() -> new IllegalArgumentException("商品不存在"));
    }
}
