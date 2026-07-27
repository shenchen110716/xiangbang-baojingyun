package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.settlement.api.SettlementApi;
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
class SettlementServiceTest {

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
    @Autowired AgreementApi agreementApi;
    @Autowired SettlementRepository settlements;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    private long pendingSettlement(String legalRepPhone, String applicantPhone, String suffix, long wageCents) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + suffix, "1101011990010" + suffix + "001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, suffix + "号工厂", "9111000000000" + suffix + "9X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, suffix + "号岗位", "描述", wageCents, legalRep)));
        long jobId = jobIdHolder.get();

        long applicant = verifiedUser(applicantPhone, "应聘者" + suffix, "1101011990010" + suffix + "002");
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
        return settlements.findByApplicationId(applicationId).orElseThrow().getId();
    }

    @Test
    void 待结算记录可以作废() {
        long settlementId = pendingSettlement("13100000005", "13100000006", "a3", 3200);

        settlementApi.voidSettlement(settlementId, "岗位取消", ops.userId());

        var view = settlementApi.findById(settlementId).orElseThrow();
        assertThat(view.status()).isEqualTo(Settlement.Status.VOIDED);
        assertThat(view.voidReason()).isEqualTo("岗位取消");
    }
}
