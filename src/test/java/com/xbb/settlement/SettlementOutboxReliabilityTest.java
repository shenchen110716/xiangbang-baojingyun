package com.xbb.settlement;

import com.xbb.AbstractOutboxEvent;
import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.engagement.internal.EngagementOutboxRelay;
import com.xbb.fund.internal.PayoutRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.settlement.api.SettlementCalculated;
import com.xbb.settlement.internal.SettlementOutboxEvent;
import com.xbb.settlement.internal.SettlementOutboxRelay;
import com.xbb.settlement.internal.SettlementOutboxRepository;
import com.xbb.settlement.internal.SettlementRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * outbox 的价值只有一条:**下游失败时事件不丢**。围绕这一条测。
 *
 * <p>注意本类没有"等一会儿看看有没有到"的写法:中继在测试里被调大到不会自动跑
 * (见 TestcontainersConfig),投递完全由测试显式驱动,因此每条断言都是确定的。
 */
// 关掉后台中继:本类要断言"中继跑之前下游拿不到",投递必须完全由测试驱动
@SpringBootTest(properties = "xbb.outbox.relay.settlement.interval-ms=3600000")
@Import({TestcontainersConfig.class, TestCodeAccessor.class,
        SettlementOutboxReliabilityTest.BreakableConsumerConfig.class})
class SettlementOutboxReliabilityTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        // 独占数据库:别的测试上下文的后台中继一直在跑,共享库里做不到
        // "没人投递之前下游拿不到"这个前提(详见 registerIsolatedProperties)
        TestcontainersConfig.registerIsolatedProperties(registry);
    }

    /** 一个可以按开关罢工的下游消费方,用来制造"投递失败"。 */
    @TestConfiguration
    static class BreakableConsumerConfig {
        static volatile boolean broken = false;

        @Bean
        BreakableConsumer breakableConsumer() {
            return new BreakableConsumer();
        }

        static class BreakableConsumer {
            @EventListener
            void on(SettlementCalculated event) {
                if (broken) {
                    throw new IllegalStateException("下游暂时不可用");
                }
            }
        }
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AgreementApi agreementApi;
    @Autowired SettlementRepository settlements;
    @Autowired SettlementOutboxRepository outbox;
    @Autowired SettlementOutboxRelay relay;
    // 履约完成事件本身也走 outbox 了,本类关掉了后台中继,得自己把它推过来
    @Autowired EngagementOutboxRelay engagementRelay;
    // spy 而非 mock:默认仍走真实实现,只在需要制造故障的那条用例里临时打桩
    @SpyBean PayoutRepository payouts;
    @Autowired ApplicationEventPublisher events;
    @Autowired @Qualifier("settlementTransactionManager") PlatformTransactionManager settlementTx;

    @AfterEach
    void 恢复下游() {
        BreakableConsumerConfig.broken = false;
        reset(payouts);
    }

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    /** 走到"履约完成",返回 applicationId。此时结算记录与 outbox 行都已落库,但尚未投递。 */
    private long completedEngagement(String legalRepPhone, String legalRepId, String workerPhone,
                                     String workerId, String orgName, String creditCode, long wageCents) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", wageCents, legalRep)));
        long jobId = jobIdHolder.get();

        long worker = verifiedUser(workerPhone, "工人" + workerPhone.substring(8), workerId);
        AtomicLong appHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = appHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                // 协议由录用事件异步触发生成,先等它到位
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        agreementApi.sign(applicationId, worker, "SMS")));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep));
        // 把 EngagementCompleted 投给结算域,结算域才会写自己的 outbox 行
        engagementRelay.publishPending();
        return applicationId;
    }

    private SettlementOutboxEvent outboxRowOf(long applicationId) {
        long settlementId = settlements.findByApplicationId(applicationId).orElseThrow().getId();
        return outbox.findAll().stream()
                .filter(row -> row.getPayload().contains("\"settlementId\":" + settlementId))
                .findFirst().orElseThrow(() -> new AssertionError("结算 " + settlementId + " 没有对应的 outbox 行"));
    }

    @Test
    void 结算记录与事件一起落库_中继跑之前下游拿不到() {
        long applicationId = completedEngagement("16200000001", "110101199001028001",
                "16200000002", "110101199001028002", "可靠一厂", "91110000000000201X", 3300);

        var settlement = settlements.findByApplicationId(applicationId).orElseThrow();
        SettlementOutboxEvent row = outboxRowOf(applicationId);
        assertThat(row.getStatus()).isEqualTo(AbstractOutboxEvent.Status.PENDING);
        assertThat(row.getEventType()).isEqualTo(SettlementCalculated.class.getName());

        // 还没投递,资金域不该已经有待发放记录
        assertThat(payouts.findBySettlementId(settlement.getId())).isEmpty();
    }

    @Test
    void 中继投递成功后事件标记已发布且下游生成待发放() {
        long applicationId = completedEngagement("16200000003", "110101199001028003",
                "16200000004", "110101199001028004", "可靠二厂", "91110000000000202X", 3400);
        long settlementId = settlements.findByApplicationId(applicationId).orElseThrow().getId();

        relay.publishPending();

        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
        assertThat(payouts.findBySettlementId(settlementId)).isPresent()
                .get().extracting(p -> p.getAmountCents()).isEqualTo(3400L);
    }

    /**
     * 钉住中继的契约:任何消费方抛异常,事件都必须留在表里可重试。
     * (这里的罢工消费方是测试替身;真实消费方的那条见
     * {@link #真实消费方写库失败时事件也必须留在表里可重试()}。)
     */
    @Test
    void 下游抛异常时事件不丢并保持可重试() {
        BreakableConsumerConfig.broken = true;
        long applicationId = completedEngagement("16200000005", "110101199001028005",
                "16200000006", "110101199001028006", "可靠三厂", "91110000000000203X", 3500);

        relay.publishPending();

        SettlementOutboxEvent row = outboxRowOf(applicationId);
        assertThat(row.getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastError()).contains("下游暂时不可用");
    }

    @Test
    void 下游恢复后重投成功且只产生一笔待发放() {
        BreakableConsumerConfig.broken = true;
        long applicationId = completedEngagement("16200000007", "110101199001028007",
                "16200000008", "110101199001028008", "可靠四厂", "91110000000000204X", 3600);
        long settlementId = settlements.findByApplicationId(applicationId).orElseThrow().getId();

        relay.publishPending();
        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);

        BreakableConsumerConfig.broken = false;
        relay.publishPending();

        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
        // 至少一次投递下最怕的事:重投变成第二笔钱
        assertThat(payouts.findAll().stream()
                .filter(p -> p.getSettlementId() == settlementId).count()).isEqualTo(1);
    }

    @Test
    void 再投一次已发布的事件不会重复扣款() {
        long applicationId = completedEngagement("16200000009", "110101199001028009",
                "16200000010", "110101199001028010", "可靠五厂", "91110000000000205X", 3700);
        long settlementId = settlements.findByApplicationId(applicationId).orElseThrow().getId();
        relay.publishPending();

        // 手工把行退回可重试状态,模拟"标记已发布的那次提交没落地"导致的重投
        SettlementOutboxEvent row = outboxRowOf(applicationId);
        row.markAttemptFailed("模拟重投");
        outbox.save(row);

        relay.publishPending();

        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
        assertThat(payouts.findAll().stream()
                .filter(p -> p.getSettlementId() == settlementId).count()).isEqualTo(1);
    }

    @Test
    void 同一个履约完成事件重复到达只生成一条结算和一条事件() {
        long applicationId = 990_001L;
        EngagementCompleted event = EngagementCompleted.of(
                applicationId, 990_101L, 990_201L, 990_301L, 3800, Instant.now());

        TransactionTemplate tx = new TransactionTemplate(settlementTx);
        tx.executeWithoutResult(status -> events.publishEvent(event));
        tx.executeWithoutResult(status -> events.publishEvent(event));

        assertThat(settlements.findByApplicationId(applicationId)).isPresent();
        assertThat(outbox.findAll().stream()
                .filter(row -> event.eventId().equals(row.getEventId())).count()).isEqualTo(1);
    }

    /**
     * 上一条用的是测试替身,只钉住了中继;这条让**真实的资金域消费方**写库失败。
     *
     * <p>它区分的是消费方的注解:若消费方用 {@code @TransactionalEventListener(AFTER_COMMIT)},
     * 它要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,这里会读到 PUBLISHED 而非 FAILED,
     * 事件就真丢了。所以这条挂,等于 outbox 白做。
     */
    @Test
    void 真实消费方写库失败时事件也必须留在表里可重试() {
        long applicationId = completedEngagement("16200000011", "110101199001028011",
                "16200000012", "110101199001028012", "可靠六厂", "91110000000000206X", 3900);
        long settlementId = settlements.findByApplicationId(applicationId).orElseThrow().getId();

        doThrow(new DataIntegrityViolationException("待发放记录写入失败")).when(payouts).save(any());
        relay.publishPending();

        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.FAILED);

        reset(payouts);
        relay.publishPending();

        assertThat(outboxRowOf(applicationId).getStatus()).isEqualTo(AbstractOutboxEvent.Status.PUBLISHED);
        assertThat(payouts.findBySettlementId(settlementId)).isPresent();
    }
}
