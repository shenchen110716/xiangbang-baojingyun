package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.internal.PostedJobRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.settlement.internal.Settlement;
import com.xbb.settlement.internal.SettlementRepository;
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
class EngagementEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AgreementApi agreementApi;
    @Autowired PostedJobRepository postedJobs;
    @Autowired SettlementRepository settlements;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    @Test
    void 应聘录用事件被结算域订阅并生成待结算记录() {
        long legalRep = verifiedUser("13200000001", "法人九", "110101199001012001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, "九号工厂", "91110000000000061X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        // 岗位域的组织副本也是异步落地的,发岗前得等它到位
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "理货员", "仓库理货", 2900, legalRep)));
        long jobId = jobIdHolder.get();
        await().atMost(Duration.ofSeconds(15)).until(() -> postedJobs.findById(jobId).isPresent());

        long applicant = verifiedUser("13200000002", "应聘者五", "110101199001012002");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();

        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2),没签不让完成
        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(applicationId, applicant, "SMS"));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep));

        await().atMost(Duration.ofSeconds(15)).until(() -> settlements.findByApplicationId(applicationId).isPresent());
        var settlement = settlements.findByApplicationId(applicationId).orElseThrow();
        assertThat(settlement.getAmountCents()).isEqualTo(2900);
        assertThat(settlement.getWorkerUserId()).isEqualTo(applicant);
        assertThat(settlement.getStatus()).isEqualTo(Settlement.Status.PENDING);
    }
}
