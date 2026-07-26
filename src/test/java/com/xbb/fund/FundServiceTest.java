package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
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
        // 代发要从监管账户扣款(§6.4.2 资金隔离),先备资再发,否则会被"余额不足"挡下
        fundApi.topUp(AccountType.USER_FUNDS, amountCents * 2, "测试备资");
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
    void 重复发放是幂等的不会重复打钱() {
        // 语义变更(Plan12):代发接入微工卡通道后,重复调用从"报错"改成"幂等空操作"。
        // 对支付类接口这才是正确语义——网络重试不该报错。真正要守住的不变量是
        // **钱只出一次**,而不是"第二次调用抛异常"。
        long payoutId = pendingPayout(1100);
        fundApi.disburse(payoutId);
        long balanceAfterFirst = fundApi.balanceOf(AccountType.USER_FUNDS);

        fundApi.disburse(payoutId);

        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(balanceAfterFirst);
        assertThat(fundApi.findById(payoutId).orElseThrow().status()).isEqualTo(Payout.Status.PAID);
    }

    @Test
    void 并发发放不会重复打钱() throws InterruptedException {
        long payoutId = pendingPayout(1200);
        long balanceBefore = fundApi.balanceOf(AccountType.USER_FUNDS);

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

        // 铁律:同一笔发放不能被并发**打两次钱**。
        // 幂等语义下"两个调用都不报错"是允许的(其中一个是空操作),
        // 所以断言的是资金侧的不变量:只扣一次款、只有一条成功的代发记录。
        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(balanceBefore - 1200);
        assertThat(fundApi.findDisbursement(payoutId).orElseThrow().status())
                .isEqualTo(com.xbb.fund.api.DisbursementStatus.SUCCESS);
        assertThat(successCount.get() + failures.size()).isEqualTo(2);
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
