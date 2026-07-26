package com.xbb.fund;

import com.xbb.AbstractOutboxEvent;
import com.xbb.AbstractOutboxRelay;
import com.xbb.OutboxAdmin;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退避、卡死判定与人工重放(运营化)。
 *
 * <p>本类**特意设了非零退避**——其余测试把它设成 0,因为它们关心的是
 * "失败了还能重试",不是"等多久再重试"。这里关心的正是后者。
 */
@SpringBootTest(properties = {
        "xbb.outbox.relay.fund.interval-ms=3600000",   // 投递完全由测试驱动
        "xbb.outbox.retry.base-ms=60000",              // 一分钟起步,足以让"还没到点"可断言
        "xbb.outbox.stuck-threshold=2"                 // 两次就算卡死,省得为了测试硬造五次失败
})
@Import({TestcontainersConfig.class, TestCodeAccessor.class,
        OutboxRetryPolicyTest.BreakableConsumerConfig.class})
class OutboxRetryPolicyTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        // 独占数据库:别的上下文的中继会抢着投递,"还没到点就不该被重投"这种断言在共享库上不成立
        TestcontainersConfig.registerIsolatedProperties(registry);
    }

    @TestConfiguration
    static class BreakableConsumerConfig {
        static volatile boolean broken = false;

        @Bean
        BreakableConsumer retryPolicyConsumer() { return new BreakableConsumer(); }

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
    @Autowired OutboxAdmin admin;

    @AfterEach
    void 恢复下游() {
        BreakableConsumerConfig.broken = false;
    }

    private FundOutboxEvent rowOf(long payoutId) {
        return outbox.findAll().stream()
                .filter(row -> row.getPayload().contains("\"payoutId\":" + payoutId))
                .findFirst().orElseThrow(() -> new AssertionError("发放 " + payoutId + " 没有对应的 outbox 行"));
    }

    private long failedPayout(long settlementId, long payeeUserId) {
        long payoutId = payouts.save(new Payout(settlementId, payeeUserId, 7_000L)).getId();
        fundApi.topUp(AccountType.USER_FUNDS, 1_000_000, "测试备资");
        BreakableConsumerConfig.broken = true;
        fundApi.disburse(payoutId);
        relay.publishPending();
        return payoutId;
    }

    @Test
    void 失败后要退避_没到点不会被重投() {
        long payoutId = failedPayout(9_800_001L, 9_800_101L);

        FundOutboxEvent row = rowOf(payoutId);
        assertThat(row.getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now());

        // 就算下游已经好了,也得等到点——不退避的话一个坏掉的下游会被以中继频率反复捶打,
        // 而且按 id 升序取批次时,足够多的坏事件会把批次占满,后面的正常事件永远排不上
        BreakableConsumerConfig.broken = false;
        relay.publishPending();

        assertThat(rowOf(payoutId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);
        assertThat(rowOf(payoutId).getAttemptCount()).isEqualTo(1);   // 没被再试一次
    }

    @Test
    void 重试到阈值就进卡死清单_带上原因和次数() {
        long payoutId = failedPayout(9_800_002L, 9_800_102L);

        // 手工把退避清掉再失败一次,凑到阈值 2
        FundOutboxEvent row = rowOf(payoutId);
        row.markAttemptFailed(row.getLastError(), Instant.now());
        outbox.save(row);
        relay.publishPending();

        AbstractOutboxRelay.StuckEvent stuck = admin.stuckEvents().stream()
                .filter(e -> e.eventId().equals(rowOf(payoutId).getEventId()))
                .findFirst().orElseThrow(() -> new AssertionError("这条事件应该已经进卡死清单"));

        assertThat(stuck.domain()).isEqualTo("fund");
        assertThat(stuck.eventType()).isEqualTo(FundsDisbursed.class.getName());
        assertThat(stuck.attemptCount()).isGreaterThanOrEqualTo(2);
        assertThat(stuck.lastError()).contains("下游暂时不可用");
    }

    @Test
    void 人工重放清零退避与失败计数_下一轮立刻重投() {
        long payoutId = failedPayout(9_800_003L, 9_800_103L);
        String eventId = rowOf(payoutId).getEventId();
        assertThat(rowOf(payoutId).getNextAttemptAt()).isAfter(Instant.now());

        BreakableConsumerConfig.broken = false;
        assertThat(admin.replay("fund", eventId)).isTrue();

        FundOutboxEvent afterReplay = rowOf(payoutId);
        assertThat(afterReplay.getStatus()).isEqualTo(AbstractOutboxEvent.Status.PENDING);
        assertThat(afterReplay.getAttemptCount()).isZero();
        assertThat(afterReplay.getNextAttemptAt()).isNull();

        relay.publishPending();
        assertThat(rowOf(payoutId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
    }

    @Test
    void 域名写错要明确报错而不是当成事件不存在() {
        assertThatThrownBy(() -> admin.replay("funds", "随便一个id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有这个域的 outbox");

        assertThat(admin.replay("fund", "根本不存在的事件id")).isFalse();
    }

    @Test
    void 汇总清单覆盖所有有中继的域() {
        assertThat(admin.domains())
                .contains("fund", "settlement", "engagement", "identity", "org", "job",
                        "agreement", "review", "broker", "mall", "profile");
    }
}
