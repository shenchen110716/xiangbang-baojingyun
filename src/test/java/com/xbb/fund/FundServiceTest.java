package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.internal.Payout;
import com.xbb.fund.internal.PayoutRepository;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class FundServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired FundApi fundApi;
    @Autowired PayoutRepository payouts;

    private static final AtomicLong SETTLEMENT_ID_SEQ = new AtomicLong(900_000);

    private long pendingPayout(long amountCents) {
        Payout payout = payouts.save(new Payout(SETTLEMENT_ID_SEQ.incrementAndGet(), 42L, amountCents));
        return payout.getId();
    }

    @Test
    void 待发放记录可以发放() {
        long payoutId = pendingPayout(1000);

        fundApi.disburse(payoutId);

        var view = fundApi.findById(payoutId).orElseThrow();
        assertThat(view.status()).isEqualTo(Payout.Status.PAID);
    }

    @Test
    void 已发放不可重复发放() {
        long payoutId = pendingPayout(1100);
        fundApi.disburse(payoutId);

        assertThatThrownBy(() -> fundApi.disburse(payoutId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待发放");
    }

    @Test
    void 并发发放只有一次能成功() throws InterruptedException {
        long payoutId = pendingPayout(1200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger();

        Runnable disburseTask = () -> {
            ready.countDown();
            awaitLatch(go);
            try {
                fundApi.disburse(payoutId);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        Thread t1 = new Thread(disburseTask);
        Thread t2 = new Thread(disburseTask);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        // 铁律:同一笔发放不能被并发执行两次(乐观锁 + 状态机检查兜底)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failures).hasSize(1);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
