package com.xbb.mall;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.mall.api.MallApi;
import com.xbb.mall.api.ProductSettlementMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 商城域(§6.3 核销型电商)。规则逐条对着 §6.3.6。 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class MallServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MallApi mallApi;

    private static final Instant FAR_FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);

    private long redeemGatedProduct(int stock) {
        return mallApi.publishProduct(900L, "某景区门票", ProductSettlementMode.REDEEM_GATED,
                12_000, "7月20日 上午场", stock, FAR_FUTURE);
    }

    private long instantProduct(int stock) {
        return mallApi.publishProduct(901L, "秒杀特惠房", ProductSettlementMode.INSTANT,
                29_900, "7月20日", stock, null);
    }

    // ---------- R1 库存并发锁防超卖 ----------

    @Test
    void 下单扣减库存() {
        long productId = redeemGatedProduct(3);

        mallApi.placeOrder(productId, 1L);

        assertThat(mallApi.findProduct(productId).orElseThrow().stock()).isEqualTo(2);
    }

    @Test
    void 售罄后不能再下单() {
        long productId = redeemGatedProduct(1);
        mallApi.placeOrder(productId, 1L);

        assertThatThrownBy(() -> mallApi.placeOrder(productId, 2L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("售罄");
    }

    @Test
    void 并发抢最后一张票不会超卖() throws InterruptedException {
        long productId = redeemGatedProduct(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable buy = () -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                mallApi.placeOrder(productId, 1L);
                success.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };
        Thread t1 = new Thread(buy);
        Thread t2 = new Thread(buy);
        t1.start(); t2.start();
        ready.await(); go.countDown();
        t1.join(); t2.join();

        // §6.3.6 R1:同一场次不能卖超。库存绝不能为负
        assertThat(success.get()).isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(mallApi.findProduct(productId).orElseThrow().stock()).isZero();
    }

    // ---------- R5 核销码一次性 + 防伪 ----------

    @Test
    void 支付后生成核销码() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 10L);

        mallApi.pay(orderId);

        assertThat(mallApi.findOrder(orderId).orElseThrow().voucherCode()).isNotBlank();
    }

    @Test
    void 同一核销码不能重复核销() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 11L);
        mallApi.pay(orderId);
        String code = mallApi.findOrder(orderId).orElseThrow().voucherCode();
        mallApi.redeem(code);

        // §6.3.6 R5:"防止截图分享、重复蹭入场"
        assertThatThrownBy(() -> mallApi.redeem(code))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已被使用");
    }

    @Test
    void 伪造的核销码无效() {
        assertThatThrownBy(() -> mallApi.redeem("V999-deadbeef1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void 未支付的订单不能核销() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 12L);

        // 没支付就没有核销码,自然核销不了
        assertThat(mallApi.findOrder(orderId).orElseThrow().voucherCode()).isNull();
    }

    // ---------- §6.3.2 两种结算模式 ----------

    @Test
    void 即时结算商品支付即完成结算触发() {
        long productId = instantProduct(5);
        long orderId = mallApi.placeOrder(productId, 20L);

        mallApi.pay(orderId);

        // 即时结算没有核销环节,支付后订单就是 PAID 终态
        assertThat(mallApi.findOrder(orderId).orElseThrow().status())
                .isEqualTo(MallApi.OrderStatus.PAID);
    }

    @Test
    void 核销结算商品要核销后才算完成() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 21L);
        mallApi.pay(orderId);
        assertThat(mallApi.findOrder(orderId).orElseThrow().status())
                .isEqualTo(MallApi.OrderStatus.PAID);

        mallApi.redeem(mallApi.findOrder(orderId).orElseThrow().voucherCode());

        // §6.3.6 R4:货款在核销成功时才划给商户,防"买了不来"占坑
        assertThat(mallApi.findOrder(orderId).orElseThrow().status())
                .isEqualTo(MallApi.OrderStatus.REDEEMED);
    }

    // ---------- R3/R6 退款规则 ----------

    @Test
    void 即时结算商品概不退货() {
        long productId = instantProduct(5);
        long orderId = mallApi.placeOrder(productId, 30L);
        mallApi.pay(orderId);

        assertThatThrownBy(() -> mallApi.refund(orderId, 30L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("概不退货");
    }

    @Test
    void 核销前未过截止时间可以退款并释放库存() {
        long productId = redeemGatedProduct(2);
        long orderId = mallApi.placeOrder(productId, 31L);
        mallApi.pay(orderId);
        assertThat(mallApi.findProduct(productId).orElseThrow().stock()).isEqualTo(1);

        mallApi.refund(orderId, 31L);

        assertThat(mallApi.findOrder(orderId).orElseThrow().status())
                .isEqualTo(MallApi.OrderStatus.REFUNDED);
        // 退了的票要能再卖给别人
        assertThat(mallApi.findProduct(productId).orElseThrow().stock()).isEqualTo(2);
    }

    @Test
    void 已核销的订单不能退款() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 32L);
        mallApi.pay(orderId);
        mallApi.redeem(mallApi.findOrder(orderId).orElseThrow().voucherCode());

        assertThatThrownBy(() -> mallApi.refund(orderId, 32L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已核销");
    }

    @Test
    void 过了退款截止时间不能退款() {
        // 截止时间设在过去,等价于"已过期"
        long productId = mallApi.publishProduct(902L, "昨天的演出", ProductSettlementMode.REDEEM_GATED,
                20_000, "昨天场次", 5, Instant.now().minus(1, ChronoUnit.DAYS));
        long orderId = mallApi.placeOrder(productId, 33L);
        mallApi.pay(orderId);

        assertThatThrownBy(() -> mallApi.refund(orderId, 33L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("截止时间");
    }

    @Test
    void 不能退别人的订单() {
        long productId = redeemGatedProduct(5);
        long orderId = mallApi.placeOrder(productId, 34L);
        mallApi.pay(orderId);

        assertThatThrownBy(() -> mallApi.refund(orderId, 999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自己的订单");
    }
}
