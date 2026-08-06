package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.attendance.api.AttendanceApi;
import com.xbb.attendance.internal.EngagedWorkerRepository;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.internal.CommissionBaseRepository;
import com.xbb.broker.internal.CommissionRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.internal.SettlementPostedJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 佣金基数 = **浮动工资**,不是发放总额。
 *
 * <p>老系统 JobComputerService 拿 floatSalary 算佣金。我们此前用的是发放总额 ——
 * 那会让佣金随基本工资一起涨,口径和老系统不一致。这里把它钉住。
 *
 * <p>号段 176,信用代码 …c xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class CommissionBaseTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AttendanceApi attendanceApi;
    @Autowired SettlementApi settlementApi;
    @Autowired BrokerApi brokerApi;
    @Autowired FundApi fundApi;
    @Autowired CommissionRepository commissions;
    @Autowired CommissionBaseRepository bases;
    @Autowired SettlementPostedJobRepository postedJobs;
    @Autowired EngagedWorkerRepository engagedWorkers;
    @Autowired com.xbb.agreement.api.AgreementApi agreementApi;

    private static final LocalDate D1 = LocalDate.of(2026, 6, 1);

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    @Test
    void 佣金按浮动工资算而不是按应发总额() {
        long boss   = verified("17600000001", "老板", "110101199001011201");
        long worker = verified("17600000002", "工人", "110101199001011202");
        long broker = verified("17600000003", "经纪人", "110101199001011203");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> brokerApi.registerBroker(broker));
        brokerApi.bindWorker(broker, worker);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "佣金基数厂", "9111000000000c01X", boss)));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "佣金基数岗", "描述", 20_000L, boss)));
        long jobId = jobH.get();
        await().atMost(Duration.ofSeconds(20)).until(() -> postedJobs.findById(jobId).isPresent());

        // 时薪 2500 基本 + 500 浮动。干 8 小时 → 应发 24000,其中浮动 4000
        settlementApi.publishPayPlan(jobId, "带浮动", "HOURLY", 2_500, 500, 0, D1, List.of(), boss);

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> appH.set(engagementApi.apply(jobId, worker)));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss));
        await().atMost(Duration.ofSeconds(20)).until(() -> engagedWorkers.findById(appId).isPresent());

        long wd = attendanceApi.upsert(appId, D1, 480, null, null, "IMPORT", null, "导入", boss);
        attendanceApi.confirm(wd, boss);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(appId, worker, "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(appId, boss));

        // 佣金基数副本应当等于浮动部分 4000,而不是应发 24000
        var settlementId = await().atMost(Duration.ofSeconds(25))
                .until(() -> settlementApi.findByApplicationId(appId).map(s -> s.id()).orElse(null),
                        java.util.Objects::nonNull);
        await().atMost(Duration.ofSeconds(25)).until(() -> bases.findById(settlementId).isPresent());
        assertThat(bases.findById(settlementId).orElseThrow().getBaseCents())
                .as("佣金基数应当是浮动工资 4000,不是应发总额 24000")
                .isEqualTo(4_000);

        // 真的发一笔,看佣金按哪个数算
        // 按单位分账之后,企业的薪水必须从**企业自己的账户**出 —— 充平台账户没用
        fundApi.topUpOrg(orgH.get(), AccountType.USER_FUNDS, 1_000_000,
                "测试备资", "cb-topup-" + orgH.get(), ops.userId());
        var payoutId = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).map(p -> p.id()).orElse(null),
                        java.util.Objects::nonNull);
        fundApi.disburse(payoutId, ops.userId());

        await().atMost(Duration.ofSeconds(25))
                .until(() -> !commissions.findAllBySettlementId(settlementId).isEmpty());
        var active = commissions.findAllBySettlementId(settlementId).stream()
                .filter(c -> c.getBrokerUserId() != null && c.getBrokerUserId() == broker)
                .findFirst().orElseThrow();

        // 主动佣金 60% × 基数 4000 = 2400。
        // 若还按应发总额算会是 60% × 24000 = 14400 —— 差六倍
        assertThat(active.getAmountCents()).isEqualTo(2_400);
        assertThat(active.getAmountCents()).isNotEqualTo(14_400);
    }
}
