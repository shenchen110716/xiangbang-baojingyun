package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class SettlementVoidedTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired SettlementApi settlementApi;
    @Autowired EngagementApi engagementApi;
    @Autowired SettlementOutboxRelay outboxRelay;
    @Autowired AgreementApi agreementApi;
    @Autowired FundApi fundApi;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    @Test
    void 结算被作废后待发放记录自动取消() {
        long legalRep = verifiedUser("15000000003", "法人十一", "110101199001021001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "十一号工厂", "91110000000000081X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "打包员", "仓库打包", 2700, legalRep)));
        long jobId = jobIdHolder.get();

        long applicant = verifiedUser("15000000004", "应聘者七", "110101199001021002");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2),没签不让完成
        agreementApi.sign(applicationId, applicant, "SMS");
        engagementApi.completeApplication(applicationId, legalRep);
        // outbox:结算事件先落库,由中继投递到下游(生产由调度驱动,测试显式推进)
        outboxRelay.publishPending();

        AtomicLong settlementIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                settlementIdHolder.set(settlementApi.findByApplicationId(applicationId).orElseThrow().id()));
        long settlementId = settlementIdHolder.get();

        AtomicLong payoutIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                payoutIdHolder.set(fundApi.findBySettlementId(settlementId).orElseThrow().id()));
        long payoutId = payoutIdHolder.get();

        settlementApi.voidSettlement(settlementId, "岗位取消");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(fundApi.findById(payoutId).orElseThrow().status())
                        .isEqualTo(com.xbb.fund.internal.Payout.Status.CANCELLED));

        // 备足资金,确保这里挡下发放的原因是"已作废"而不是"余额不足"
        fundApi.topUp(AccountType.USER_FUNDS, 1_000_000, "测试备资");
        assertThatThrownBy(() -> fundApi.disburse(payoutId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已作废");
    }
}
