package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 计薪方案的守卫。
 *
 * <p>守的都是**工资会算错**的路径:同时存在两个生效方案、改方案把历史工资单的依据抹掉、
 * 别人替你设方案、全零方案让人白干。
 *
 * <p>号段 174,信用代码 …a xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class PayPlanTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired SettlementApi settlementApi;
    @Autowired SettlementPostedJobRepository postedJobs;

    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    private record Job(long boss, long jobId) { }

    private Job job(String phone, String idNo, String creditCode) {
        long boss = verified(phone, "老板", idNo);
        AtomicLong orgHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgHolder.set(orgApi.submit(OrgType.FACTORY, "计薪厂" + creditCode, creditCode, boss)));
        orgApi.approve(orgHolder.get(), ops.userId());

        AtomicLong jobHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobHolder.set(jobApi.postJob(orgHolder.get(), "计薪岗", "描述", 20_000L, boss)));
        long jobId = jobHolder.get();
        // 岗位副本经 outbox 异步到达结算域
        await().atMost(Duration.ofSeconds(20)).until(() -> postedJobs.findById(jobId).isPresent());
        return new Job(boss, jobId);
    }

    @Test
    void 发新版本会让旧版失效_同时只有一个生效() {
        Job j = job("17400000001", "110101199001011001", "9111000000000a01X");

        long v1 = settlementApi.publishPayPlan(j.jobId(), "初版", "HOURLY",
                2_500, 500, 0, FROM, List.of(), j.boss());
        long v2 = settlementApi.publishPayPlan(j.jobId(), "调薪版", "HOURLY",
                3_000, 800, 0, FROM.plusMonths(1), List.of(), j.boss());

        var all = settlementApi.listPayPlans(j.jobId(), j.boss());
        assertThat(all).hasSize(2);
        // **同时只能有一个生效** —— 少了这条,算薪时不知道用哪个,
        // 而两个方案各自都合法,查起来毫无线索
        assertThat(all.stream().filter(p -> p.status().equals("ACTIVE"))).hasSize(1);
        assertThat(settlementApi.activePayPlan(j.jobId(), j.boss()).orElseThrow().id()).isEqualTo(v2);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void 旧版本失效但不删除_已出工资单还要靠它解释金额() {
        Job j = job("17400000002", "110101199001011002", "9111000000000a02X");
        long v1 = settlementApi.publishPayPlan(j.jobId(), "初版", "HOURLY",
                2_500, 0, 0, FROM, List.of(), j.boss());
        settlementApi.publishPayPlan(j.jobId(), "二版", "HOURLY",
                3_000, 0, 0, FROM.plusDays(30), List.of(), j.boss());

        var old = settlementApi.listPayPlans(j.jobId(), j.boss()).stream()
                .filter(p -> p.id() == v1).findFirst();
        assertThat(old).as("旧版本不该被删除").isPresent();
        assertThat(old.orElseThrow().status()).isEqualTo("EXPIRED");
        assertThat(old.orElseThrow().effectiveTo()).isNotNull();
        // 金额保持不变 —— 版本不可变是这整套设计的前提
        assertThat(old.orElseThrow().basicSalaryCents()).isEqualTo(2_500);
    }

    @Test
    void 版本号递增() {
        Job j = job("17400000003", "110101199001011003", "9111000000000a03X");
        settlementApi.publishPayPlan(j.jobId(), "v1", "DAILY", 20_000, 0, 0, FROM, List.of(), j.boss());
        settlementApi.publishPayPlan(j.jobId(), "v2", "DAILY", 21_000, 0, 0, FROM, List.of(), j.boss());
        settlementApi.publishPayPlan(j.jobId(), "v3", "DAILY", 22_000, 0, 0, FROM, List.of(), j.boss());

        assertThat(settlementApi.listPayPlans(j.jobId(), j.boss()))
                .extracting(SettlementApi.PayPlanView::version)
                .containsExactly(3, 2, 1);
    }

    @Test
    void 调整项跟着版本走() {
        Job j = job("17400000004", "110101199001011004", "9111000000000a04X");
        settlementApi.publishPayPlan(j.jobId(), "带扣款", "HOURLY", 2_500, 0, 0, FROM,
                List.of(new SettlementApi.FactorSpec("BONUS", "全勤奖", 10_000),
                        new SettlementApi.FactorSpec("DEDUCTION", "宿舍水电", 3_000)), j.boss());

        var active = settlementApi.activePayPlan(j.jobId(), j.boss()).orElseThrow();
        assertThat(active.factors()).hasSize(2);
        assertThat(active.factors()).extracting(SettlementApi.FactorSpec::name)
                .containsExactlyInAnyOrder("全勤奖", "宿舍水电");
    }

    @Test
    void 不是法人代表不能设方案() {
        Job j = job("17400000005", "110101199001011005", "9111000000000a05X");
        long outsider = verified("17400000006", "路人", "110101199001011006");

        assertThatThrownBy(() -> settlementApi.publishPayPlan(j.jobId(), "越权", "HOURLY",
                2_500, 0, 0, FROM, List.of(), outsider))
                .hasMessageContaining("法人代表");
        assertThatThrownBy(() -> settlementApi.listPayPlans(j.jobId(), outsider))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 全零方案被拒绝() {
        Job j = job("17400000007", "110101199001011007", "9111000000000a06X");
        // 全零方案算出来永远是 0 工资。让它建成功等于埋一个"发不出钱"的雷,
        // 而且要等到发工资那天才炸
        assertThatThrownBy(() -> settlementApi.publishPayPlan(j.jobId(), "空方案", "HOURLY",
                0, 0, 0, FROM, List.of(), j.boss()))
                .hasMessageContaining("至少要有一项");
    }

    @Test
    void 不支持的计薪方式与调整项类型被拒() {
        Job j = job("17400000008", "110101199001011008", "9111000000000a07X");
        assertThatThrownBy(() -> settlementApi.publishPayPlan(j.jobId(), "x", "WEEKLY",
                2_500, 0, 0, FROM, List.of(), j.boss()))
                .hasMessageContaining("计薪方式");
        assertThatThrownBy(() -> settlementApi.publishPayPlan(j.jobId(), "x", "HOURLY",
                2_500, 0, 0, FROM,
                List.of(new SettlementApi.FactorSpec("REFUND", "退款", 100)), j.boss()))
                .hasMessageContaining("调整项类型");
    }

    @Test
    void 调整项金额必须为正() {
        Job j = job("17400000009", "110101199001011009", "9111000000000a08X");
        // 金额存正数、方向由类型决定,是为了不让两种写法并存 —— 迟早有人加错符号
        assertThatThrownBy(() -> settlementApi.publishPayPlan(j.jobId(), "x", "HOURLY",
                2_500, 0, 0, FROM,
                List.of(new SettlementApi.FactorSpec("DEDUCTION", "负数扣款", -100)), j.boss()))
                .hasMessageContaining("正数");
    }

    @Test
    void 岗位副本还没落地时明确报错() {
        Job j = job("17400000010", "110101199001011010", "9111000000000a09X");
        // 用一个不存在的岗位:错误要说清是"副本没落地",而不是含糊的空指针
        assertThatThrownBy(() -> settlementApi.publishPayPlan(77_777_777L, "x", "HOURLY",
                2_500, 0, 0, FROM, List.of(), j.boss()))
                .hasMessageContaining("岗位不存在");
    }
}
