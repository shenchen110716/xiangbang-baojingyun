package com.xbb.notification;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.agreement.api.AgreementGenerated;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.settlement.internal.SettlementOutboxRelay;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.notification.api.NotificationApi;
import com.xbb.notification.api.NotificationType;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.settlement.api.SettlementApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 通知统一出口(§4.2):各域只发事件,通知域决定要不要打扰人、说什么。 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class NotificationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired SettlementOutboxRelay outboxRelay;
    @Autowired AgreementApi agreementApi;
    @Autowired SettlementApi settlementApi;
    @Autowired FundApi fundApi;
    @Autowired NotificationApi notificationApi;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    /** 走到"已录用"(此时协议已生成),返回 [applicationId, legalRep, worker]。 */
    private long[] accepted(String legalRepPhone, String legalRepId, String workerPhone,
                             String workerIdNumber, String orgName, String creditCode) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30_000, legalRep)));
        long jobId = jobIdHolder.get();

        long worker = verifiedUser(workerPhone, "工人" + workerPhone.substring(8), workerIdNumber);
        AtomicLong appHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = appHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        return new long[]{applicationId, legalRep, worker};
    }

    @Test
    void 录用生成协议后工人收到待签通知() {
        long[] ids = accepted("16100000001", "110101199001027001",
                "16100000002", "110101199001027002", "通知一厂", "91110000000000191X");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationApi.inbox(ids[2], 20))
                        .extracting(NotificationApi.NotificationView::type)
                        .contains(NotificationType.AGREEMENT_PENDING));
    }

    @Test
    void 履约完成后工人收到可评价通知() {
        long[] ids = accepted("16100000003", "110101199001027003",
                "16100000004", "110101199001027004", "通知二厂", "91110000000000192X");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                // 协议由录用事件异步触发生成,先等它到位
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        agreementApi.sign(ids[0], ids[2], "SMS")));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(ids[0], ids[1]));
        // outbox:结算事件先落库,由中继投递到下游(生产由调度驱动,测试显式推进)
        outboxRelay.publishPending();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationApi.inbox(ids[2], 20))
                        .extracting(NotificationApi.NotificationView::type)
                        .contains(NotificationType.ENGAGEMENT_COMPLETED));
    }

    @Test
    void 工资发放后工人收到到账通知且金额按元展示() {
        long[] ids = accepted("16100000005", "110101199001027005",
                "16100000006", "110101199001027006", "通知三厂", "91110000000000193X");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                // 协议由录用事件异步触发生成,先等它到位
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        agreementApi.sign(ids[0], ids[2], "SMS")));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(ids[0], ids[1]));
        // outbox:结算事件先落库,由中继投递到下游(生产由调度驱动,测试显式推进)
        outboxRelay.publishPending();

        AtomicLong payoutHolder = new AtomicLong();
        AtomicLong orgHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            long settlementId = settlementApi.findByApplicationId(ids[0]).orElseThrow().id();
            var view = fundApi.findBySettlementId(settlementId).orElseThrow();
            payoutHolder.set(view.id());
            orgHolder.set(view.orgId() == null ? 0L : view.orgId());
        });
        // 按单位分账之后,企业的薪水必须从**企业自己的账户**出 —— 充平台账户没用
        fundApi.topUpOrg(orgHolder.get(), AccountType.USER_FUNDS, 1_000_000,
                "备资", "nt-topup-" + orgHolder.get(), ops.userId());
        fundApi.disburse(payoutHolder.get(), ops.userId());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<NotificationApi.NotificationView> inbox = notificationApi.inbox(ids[2], 20);
            assertThat(inbox).extracting(NotificationApi.NotificationView::type)
                    .contains(NotificationType.WAGE_DISBURSED);
            NotificationApi.NotificationView wage = inbox.stream()
                    .filter(n -> n.type() == NotificationType.WAGE_DISBURSED).findFirst().orElseThrow();
            // 别让用户看到 "30000" 以为是三万
            assertThat(wage.body()).contains("300.00 元").contains("完税凭证");
        });
    }

    @Test
    void 同一事件重复投递只产生一条通知() {
        long[] ids = accepted("16100000007", "110101199001027007",
                "16100000008", "110101199001027008", "通知四厂", "91110000000000194X");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationApi.inbox(ids[2], 20)).isNotEmpty());

        long countBefore = notificationApi.inbox(ids[2], 50).stream()
                .filter(n -> n.type() == NotificationType.AGREEMENT_PENDING).count();
        assertThat(countBefore).isEqualTo(1);

        // **真的再投一次。** 这条用例以前只断言"正常路径产生了 1 条",
        // 全方法没有任何一行重复投递——名字说的是幂等,验的却不是,
        // 给的是虚假的幂等信心。至少一次投递是 outbox 的既定语义,必须真的重放。
        // 必须用**同一个业务对象**重投:幂等键是 (收件人, 类型, agreementId),
        // 伪造一个新的 agreementId 等于换了业务对象,那不叫重复投递。
        long agreementId = agreementApi.findByApplicationId(ids[0], ops.userId()).orElseThrow().id();
        events.publishEvent(new AgreementGenerated(
                agreementId, ids[0], ids[2], ids[1], "hash-repeat", java.time.Instant.now()));

        // 幂等键是 (收件人, 类型, 业务对象),重复投递应被吸收
        assertThat(notificationApi.inbox(ids[2], 50).stream()
                .filter(n -> n.type() == NotificationType.AGREEMENT_PENDING).count())
                .isEqualTo(1);
    }

    @Test
    void 未读数与标记已读() {
        long[] ids = accepted("16100000009", "110101199001027009",
                "16100000010", "110101199001027010", "通知五厂", "91110000000000195X");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationApi.unreadCount(ids[2])).isPositive());

        long id = notificationApi.inbox(ids[2], 20).get(0).id();
        notificationApi.markRead(id, ids[2]);

        assertThat(notificationApi.inbox(ids[2], 20).stream()
                .filter(n -> n.id() == id).findFirst().orElseThrow().read()).isTrue();
    }

    @Test
    void 不能标记别人的通知为已读() {
        long[] ids = accepted("16100000011", "110101199001027011",
                "16100000012", "110101199001027012", "通知六厂", "91110000000000196X");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notificationApi.inbox(ids[2], 20)).isNotEmpty());
        long id = notificationApi.inbox(ids[2], 20).get(0).id();

        assertThatThrownBy(() -> notificationApi.markRead(id, ids[1]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自己的通知");
    }
}
