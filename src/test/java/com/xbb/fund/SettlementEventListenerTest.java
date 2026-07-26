package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.internal.Payout;
import com.xbb.fund.internal.PayoutRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
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
    @Autowired EngagementApi engagementApi;
    @Autowired PayoutRepository payouts;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    @Test
    void 结算已算出金额后资金域生成待发放记录() {
        long legalRep = verifiedUser("15000000001", "法人十", "110101199001020001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "十号工厂", "91110000000000071X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "分拣员", "仓库分拣", 3100, legalRep)));
        long jobId = jobIdHolder.get();

        long applicant = verifiedUser("15000000002", "应聘者六", "110101199001020002");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();

        engagementApi.acceptApplication(applicationId, legalRep);
        engagementApi.completeApplication(applicationId, legalRep);

        await().atMost(Duration.ofSeconds(5)).until(() -> !payouts.findAll().isEmpty());
        Payout payout = payouts.findAll().stream()
                .filter(p -> p.getPayeeUserId() == applicant)
                .findFirst().orElseThrow();
        assertThat(payout.getAmountCents()).isEqualTo(3100);
        assertThat(payout.getStatus()).isEqualTo(Payout.Status.PENDING);
    }
}
