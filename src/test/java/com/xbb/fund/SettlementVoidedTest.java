package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
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
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class SettlementVoidedTest {

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

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    @Test
    void 结算被作废后待发放记录自动取消() {
        long legalRep = verifiedUser("15000000003", "法人十一", "110101199001021001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, "十一号工厂", "91110000000000081X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "打包员", "仓库打包", 2700, legalRep)));
        long jobId = jobIdHolder.get();

        long applicant = verifiedUser("15000000004", "应聘者七", "110101199001021002");
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
        // outbox:结算事件先落库,由中继投递到下游(生产由调度驱动,测试显式推进)
        outboxRelay.publishPending();

        AtomicLong settlementIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                settlementIdHolder.set(settlementApi.findByApplicationId(applicationId).orElseThrow().id()));
        long settlementId = settlementIdHolder.get();

        AtomicLong payoutIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                payoutIdHolder.set(fundApi.findBySettlementId(settlementId).orElseThrow().id()));
        long payoutId = payoutIdHolder.get();

        settlementApi.voidSettlement(settlementId, "岗位取消", ops.userId());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(fundApi.findById(payoutId, ops.userId()).orElseThrow().status())
                        .isEqualTo(com.xbb.fund.api.PayoutStatus.CANCELLED));

        // **不备资也该报"已作废"。**状态检查排在动钱之前,
        // 所以这里根本走不到扣款那一步 —— 原来它在扣完款才拦,
        // 于是账户一有问题就报成"余额不足",运营照着去备资,备完还是发不出去
        assertThatThrownBy(() -> fundApi.disburse(payoutId, ops.userId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已作废");
    }
}
