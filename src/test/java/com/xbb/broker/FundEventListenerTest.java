package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.internal.Commission;
import com.xbb.broker.internal.CommissionRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.settlement.internal.SettlementOutboxRelay;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class FundEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired SettlementApi settlementApi;
    @Autowired EngagementApi engagementApi;
    @Autowired SettlementOutboxRelay outboxRelay;
    @Autowired AgreementApi agreementApi;
    @Autowired FundApi fundApi;
    @Autowired BrokerApi brokerApi;
    @Autowired CommissionRepository commissions;
    @Autowired com.xbb.broker.internal.BrokerVerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(15)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    /** 走完整"入驻→审核→发岗位→报名→录用"链路,applicant 由调用方传入(可能已经在别处实名过)。 */
    private long settlementFor(long applicantUserId, String legalRepPhone, String suffix, long wageCents) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + suffix, "1101011990010" + suffix + "005");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, suffix + "号工厂佣金", "9111000000" + suffix + "005X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, suffix + "号岗位佣金", "描述", wageCents, legalRep)));
        long jobId = jobIdHolder.get();

        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicantUserId)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2),没签不让完成
        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(applicationId, applicantUserId, "SMS"));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep));
        // outbox:结算事件先落库,由中继投递到下游(生产由调度驱动,测试显式推进)
        outboxRelay.publishPending();

        AtomicLong settlementIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                settlementIdHolder.set(settlementApi.findByApplicationId(applicationId).orElseThrow().id()));
        return settlementIdHolder.get();
    }

    private long disburse(long settlementId) {
        AtomicLong payoutIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                payoutIdHolder.set(fundApi.findBySettlementId(settlementId).orElseThrow().id()));
        // 代发要从监管账户扣款(§6.4.2),先备资
        fundApi.topUp(AccountType.USER_FUNDS, 1_000_000, "测试备资");
        fundApi.disburse(payoutIdHolder.get(), ops.userId());
        return payoutIdHolder.get();
    }

    @Test
    void 资金域发放事件被经纪人域订阅并生成佣金() {
        long broker = verifiedUser("13000000020", "孙经纪五", "110101199001011020");
        brokerApi.registerBroker(broker);
        long worker = verifiedUser("13000000021", "工人四", "110101199001011021");
        brokerApi.bindWorker(broker, worker);

        long settlementId = settlementFor(worker, "13000000022", "b1", 5000);
        disburse(settlementId);

        await().atMost(Duration.ofSeconds(15)).until(() -> commissions.findBySettlementId(settlementId).isPresent());

        // 六档分账(此前是单一 10%,现在照搬老系统的模型,比例来自参数中心)。
        // 基数 5000:主动 60% = 3000;剩余 2000 里 平台 20% = 400、被动池 30% = 600、服务站 50% = 1000。
        // 这个经纪人**没有上级也没挂靠服务站**,所以后两档不产生记录 ——
        // 少分是安全的,把它们转给别人才是错的。
        var all = commissions.findAllBySettlementId(settlementId);
        assertThat(all).hasSize(1);
        var commission = all.get(0);
        assertThat(commission.getTier()).isEqualTo(Commission.Tier.ACTIVE);
        assertThat(commission.getBrokerUserId()).isEqualTo(broker);
        assertThat(commission.getAmountCents()).isEqualTo(3_000);
        assertThat(commission.getStatus()).isEqualTo(Commission.Status.PENDING);
        // 分出去的绝不能超过基数
        assertThat(all.stream().mapToLong(Commission::getAmountCents).sum())
                .isLessThanOrEqualTo(5_000);
    }

    @Test
    void 工人没绑经纪人不生成佣金() {
        long worker = verifiedUser("13000000023", "工人五", "110101199001011023");
        long settlementId = settlementFor(worker, "13000000024", "b2", 4000);

        disburse(settlementId);

        // 预期结果是"始终没有"而不是"最终出现",象征性等一下确保同步链路已经跑完。
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        assertThat(commissions.findBySettlementId(settlementId)).isEmpty();
    }
}
