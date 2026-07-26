package com.xbb.fund;

import com.xbb.AbstractOutboxEvent;
import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.fund.internal.FundOutboxEvent;
import com.xbb.fund.internal.FundOutboxRelay;
import com.xbb.fund.internal.FundOutboxRepository;
import com.xbb.fund.internal.Payout;
import com.xbb.fund.internal.PayoutRepository;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资金域 outbox(§6.4)。`FundsDisbursed` 丢了的后果**不会自愈**:
 * 经纪人佣金不生成、盈亏账少一笔、工人收不到到账通知,而且不会再有第二次发放事件补上。
 */
// 关掉后台中继,投递完全由测试驱动;默认值在 src/test/resources/application.properties
@SpringBootTest(properties = "xbb.outbox.relay.interval-ms=3600000")
@Import({TestcontainersConfig.class, TestCodeAccessor.class,
        FundOutboxReliabilityTest.BreakableConsumerConfig.class})
class FundOutboxReliabilityTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        // 独占数据库:别的测试上下文的后台中继一直在跑,共享库里做不到
        // "没人投递之前下游拿不到"这个前提(详见 registerIsolatedProperties)
        TestcontainersConfig.registerIsolatedProperties(registry);
    }

    @TestConfiguration
    static class BreakableConsumerConfig {
        static volatile boolean broken = false;

        @Bean
        BreakableConsumer fundBreakableConsumer() { return new BreakableConsumer(); }

        static class BreakableConsumer {
            @EventListener
            void on(FundsDisbursed event) {
                if (broken) {
                    throw new IllegalStateException("下游暂时不可用");
                }
            }
        }
    }

    @Autowired FundApi fundApi;
    @Autowired PayoutRepository payouts;
    @Autowired FundOutboxRepository outbox;
    @Autowired FundOutboxRelay relay;

    @AfterEach
    void 恢复下游() {
        BreakableConsumerConfig.broken = false;
    }

    private FundOutboxEvent outboxRowOf(long payoutId) {
        return outbox.findAll().stream()
                .filter(row -> row.getPayload().contains("\"payoutId\":" + payoutId))
                .findFirst().orElseThrow(() -> new AssertionError("发放 " + payoutId + " 没有对应的 outbox 行"));
    }

    private long pendingPayout(long settlementId, long payeeUserId, long amountCents) {
        return payouts.save(new Payout(settlementId, payeeUserId, amountCents)).getId();
    }

    @Test
    void 发放成功后事件与账本同事务落库_中继跑之前下游拿不到() {
        long payoutId = pendingPayout(9_600_001L, 9_600_101L, 5_000L);
        fundApi.topUp(AccountType.USER_FUNDS, 1_000_000, "测试备资");

        fundApi.disburse(payoutId);

        assertThat(fundApi.findById(payoutId).orElseThrow().status()).isEqualTo(Payout.Status.PAID);
        assertThat(outboxRowOf(payoutId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PENDING);
        assertThat(outboxRowOf(payoutId).getEventType()).isEqualTo(FundsDisbursed.class.getName());
    }

    @Test
    void 下游炸了钱照发但事件留在表里可重试_恢复后重投成功() {
        long payoutId = pendingPayout(9_600_002L, 9_600_102L, 6_000L);
        fundApi.topUp(AccountType.USER_FUNDS, 1_000_000, "测试备资");
        BreakableConsumerConfig.broken = true;

        fundApi.disburse(payoutId);
        relay.publishPending();

        // 钱已经出去了,这是既成事实;不能因为下游炸了就假装没发过
        assertThat(fundApi.findById(payoutId).orElseThrow().status()).isEqualTo(Payout.Status.PAID);
        FundOutboxEvent row = outboxRowOf(payoutId);
        assertThat(row.getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("下游暂时不可用");

        BreakableConsumerConfig.broken = false;
        relay.publishPending();

        assertThat(outboxRowOf(payoutId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
    }
}
