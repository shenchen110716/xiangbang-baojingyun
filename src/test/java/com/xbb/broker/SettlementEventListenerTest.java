package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.internal.Commission;
import com.xbb.broker.internal.CommissionRepository;
import com.xbb.identity.TestCodeAccessor;
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
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class SettlementEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired SettlementApi settlementApi;
    @Autowired BrokerApi brokerApi;
    @Autowired CommissionRepository commissions;
    @Autowired com.xbb.broker.internal.BrokerVerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(5)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    /** 走完整"入驻→审核→发岗位→报名→录用"链路,applicant 由调用方传入(可能已经在别处实名过)。 */
    private long settlementFor(long applicantUserId, String legalRepPhone, String suffix, long wageCents) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + suffix, "1101011990010" + suffix + "005");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, suffix + "号工厂佣金", "9111000000" + suffix + "005X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, suffix + "号岗位佣金", "描述", wageCents, legalRep)));
        long jobId = jobIdHolder.get();

        long applicationId = jobApi.apply(jobId, applicantUserId);
        jobApi.acceptApplication(applicationId, legalRep);

        AtomicLong settlementIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                settlementIdHolder.set(settlementApi.findByApplicationId(applicationId).orElseThrow().id()));
        return settlementIdHolder.get();
    }

    @Test
    void 结算支付事件被经纪人域订阅并生成佣金() {
        long broker = verifiedUser("13000000020", "孙经纪五", "110101199001011020");
        brokerApi.registerBroker(broker);
        long worker = verifiedUser("13000000021", "工人四", "110101199001011021");
        brokerApi.bindWorker(broker, worker);

        long settlementId = settlementFor(worker, "13000000022", "b1", 5000);
        settlementApi.pay(settlementId);

        await().atMost(Duration.ofSeconds(5)).until(() -> commissions.findBySettlementId(settlementId).isPresent());
        var commission = commissions.findBySettlementId(settlementId).orElseThrow();
        assertThat(commission.getBrokerUserId()).isEqualTo(broker);
        assertThat(commission.getAmountCents()).isEqualTo(500); // 5000 * 10 / 100
        assertThat(commission.getStatus()).isEqualTo(Commission.Status.PENDING);
    }

    @Test
    void 工人没绑经纪人不生成佣金() {
        long worker = verifiedUser("13000000023", "工人五", "110101199001011023");
        long settlementId = settlementFor(worker, "13000000024", "b2", 4000);

        settlementApi.pay(settlementId);

        // 预期结果是"始终没有"而不是"最终出现",象征性等一下确保同步链路已经跑完。
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        assertThat(commissions.findBySettlementId(settlementId)).isEmpty();
    }
}
