package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
import com.xbb.attendance.api.AttendanceApi;
import com.xbb.attendance.internal.EngagedWorkerRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.internal.SettlementPostedJobRepository;
import com.xbb.settlement.internal.SettlementRepository;
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
 * 计薪方案 + 考勤 → 工资单的整条链路。
 *
 * <p>这是钱的主路,所以端到端验而不是只验各段:
 * 方案对了、考勤对了,但接线接错一样发错钱,而那种错在单元测试里看不见。
 *
 * <p>号段 175,信用代码 …b xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class PayPlanSettlementTest {

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
    @Autowired SettlementRepository settlements;
    @Autowired SettlementPostedJobRepository postedJobs;
    @Autowired EngagedWorkerRepository engagedWorkers;
    @Autowired com.xbb.agreement.api.AgreementApi agreementApi;

    private static final LocalDate D1 = LocalDate.of(2026, 5, 6);

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    private record Scene(long boss, long worker, long jobId, long applicationId) { }

    /** 搭到"已录用且协议已签"——完成履约的前置门禁。 */
    private Scene scene(String bossPhone, String bossId, String workerPhone, String workerId, String code) {
        long boss = verified(bossPhone, "老板", bossId);
        long worker = verified(workerPhone, "工人", workerId);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "计薪链路厂" + code, code, boss)));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "计薪链路岗", "描述", 20_000L, boss)));
        long jobId = jobH.get();
        await().atMost(Duration.ofSeconds(20)).until(() -> postedJobs.findById(jobId).isPresent());

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> appH.set(engagementApi.apply(jobId, worker)));
        long applicationId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(applicationId, boss));
        await().atMost(Duration.ofSeconds(20)).until(() -> engagedWorkers.findById(applicationId).isPresent());
        return new Scene(boss, worker, jobId, applicationId);
    }

    /** 签协议后完成履约。协议是异步生成的,要等。 */
    private void complete(Scene s) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(s.applicationId(), s.worker(), "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(s.applicationId(), s.boss()));
    }

    private void recordAndConfirm(Scene s, LocalDate day, int minutes) {
        long id = attendanceApi.upsert(s.applicationId(), day, minutes, null, null,
                "IMPORT", null, "测试导入", s.boss());
        attendanceApi.confirm(id, s.boss());
    }

    @Test
    void 有生效方案时按方案与已确认工时算钱() {
        Scene s = scene("17500000001", "110101199001011101", "17500000002", "110101199001011102",
                "9111000000000b01X");
        // 时薪 2500 分,浮动 500 分
        settlementApi.publishPayPlan(s.jobId(), "时薪方案", "HOURLY",
                2_500, 500, 0, D1, List.of(), s.boss());

        recordAndConfirm(s, D1, 480);              // 8 小时
        recordAndConfirm(s, D1.plusDays(1), 240);  // 4 小时
        complete(s);

        var st = await().atMost(Duration.ofSeconds(20))
                .until(() -> settlements.findByApplicationId(s.applicationId()).orElse(null),
                        java.util.Objects::nonNull);

        // 12 小时 × (2500 + 500) = 36000 分。**不是岗位工价那个 20000**
        assertThat(st.getAmountCents()).isEqualTo(36_000);
        assertThat(st.getMinutes()).isEqualTo(720);
        assertThat(st.getPayPlanId()).as("要记住按哪个方案算的").isNotNull();
        assertThat(st.getBreakdown()).as("明细要存下来,事后能解释金额").contains("基本工资");
    }

    @Test
    void 没有生效方案时退回按岗位工价一口价() {
        Scene s = scene("17500000003", "110101199001011103", "17500000004", "110101199001011104",
                "9111000000000b02X");
        // 不发布方案,也不录考勤
        complete(s);

        var st = await().atMost(Duration.ofSeconds(20))
                .until(() -> settlements.findByApplicationId(s.applicationId()).orElse(null),
                        java.util.Objects::nonNull);

        // **已经在跑的履约链路不该因为这次改动断掉。**
        // 计薪方案是后加的、按岗位逐个启用的。
        assertThat(st.getAmountCents()).isEqualTo(20_000);
        assertThat(st.getPayPlanId()).as("退回时记 null,一眼能看出这笔是老口径").isNull();
    }

    @Test
    void 草稿态考勤不计入工资() {
        Scene s = scene("17500000005", "110101199001011105", "17500000006", "110101199001011106",
                "9111000000000b03X");
        settlementApi.publishPayPlan(s.jobId(), "时薪方案", "HOURLY",
                2_500, 0, 0, D1, List.of(), s.boss());

        recordAndConfirm(s, D1, 480);                       // 确认
        attendanceApi.upsert(s.applicationId(), D1.plusDays(1), 480, null, null,
                "IMPORT", null, "只录不确认", s.boss());     // 不确认
        complete(s);

        var st = await().atMost(Duration.ofSeconds(20))
                .until(() -> settlements.findByApplicationId(s.applicationId()).orElse(null),
                        java.util.Objects::nonNull);

        // 草稿态还可能被订正,拿它计薪等于按未定稿的数字发钱
        assertThat(st.getMinutes()).isEqualTo(480);
        assertThat(st.getAmountCents()).isEqualTo(20_000);   // 8 小时 × 2500
    }

    @Test
    void 扣款项会从工资里扣掉() {
        Scene s = scene("17500000007", "110101199001011107", "17500000008", "110101199001011108",
                "9111000000000b04X");
        settlementApi.publishPayPlan(s.jobId(), "带扣款", "HOURLY", 2_500, 0, 0, D1,
                List.of(new SettlementApi.FactorSpec("BONUS", "全勤奖", 10_000),
                        new SettlementApi.FactorSpec("DEDUCTION", "宿舍水电", 3_000)), s.boss());

        recordAndConfirm(s, D1, 480);
        complete(s);

        var st = await().atMost(Duration.ofSeconds(20))
                .until(() -> settlements.findByApplicationId(s.applicationId()).orElse(null),
                        java.util.Objects::nonNull);

        // 20000 + 10000 - 3000
        assertThat(st.getAmountCents()).isEqualTo(27_000);
        assertThat(st.getBreakdown()).contains("全勤奖").contains("宿舍水电");
    }

    @Test
    void 有方案但零考勤时工资为零而不是回退到一口价() {
        Scene s = scene("17500000009", "110101199001011109", "17500000010", "110101199001011110",
                "9111000000000b05X");
        settlementApi.publishPayPlan(s.jobId(), "时薪方案", "HOURLY",
                2_500, 0, 0, D1, List.of(), s.boss());
        // 一天考勤都没录
        complete(s);

        var st = await().atMost(Duration.ofSeconds(20))
                .until(() -> settlements.findByApplicationId(s.applicationId()).orElse(null),
                        java.util.Objects::nonNull);

        // 启用了方案就按方案算。回退到一口价会让"考勤没录"变成"照发工资",
        // 而那正是计薪方案要解决的问题
        assertThat(st.getAmountCents()).isZero();
        assertThat(st.getPayPlanId()).isNotNull();
    }
}
