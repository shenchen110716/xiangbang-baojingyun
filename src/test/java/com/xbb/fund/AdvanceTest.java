package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.api.AdvanceApi;
import com.xbb.fund.api.FundApi;
import com.xbb.fund.internal.AdvanceRepaymentRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 借支与还款(老系统 M8「借押保」)。
 *
 * <p>守的都是**钱会算错**的路径:超额度借出、重复抵扣、扣成负工资、
 * 还款金额超过欠款、已还过的被撤销。
 *
 * <p>号段 180,信用代码 …g xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class AdvanceTest {

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
    @Autowired AgreementApi agreementApi;
    @Autowired AdvanceApi advanceApi;
    @Autowired FundApi fundApi;
    @Autowired OpsApi opsApi;
    @Autowired SettlementRepository settlements;
    @Autowired AdvanceRepaymentRepository repayments;
    /** 用来把同一条事件再发一遍 —— 中继就是这么重投的。 */
    @Autowired org.springframework.context.ApplicationEventPublisher events;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    // ─────────────── 额度 ───────────────

    @Test
    void 超出额度的借支被拒绝() {
        long worker = verified("18000000001", "工人", "110101199001011901");
        long limit = opsApi.settingInt(SettingKeys.ADVANCE_MAX_OUTSTANDING_CENTS, 300_000);

        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, limit + 1, "买工具", ops.userId()))
                .hasMessageContaining("超出借支额度");
    }

    @Test
    void 多次小额借支不能绕过额度() {
        long worker = verified("18000000002", "工人", "110101199001011902");
        long limit = opsApi.settingInt(SettingKeys.ADVANCE_MAX_OUTSTANDING_CENTS, 300_000);

        advanceApi.grantAdvance(worker, limit - 10_000, "第一笔", ops.userId());
        // **额度要连同已欠的一起算。**只看单笔的话,借十次小额就绕过去了 ——
        // 而那正是"借支不超可用额度"这条规则想防的事
        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, 20_000, "第二笔", ops.userId()))
                .hasMessageContaining("超出借支额度");
    }

    @Test
    void 借支要填事由() {
        long worker = verified("18000000003", "工人", "110101199001011903");
        // 平台先垫钱,事后要说得清为什么垫。没有事由的借支在对账时是无解的
        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, 10_000, "  ", ops.userId()))
                .hasMessageContaining("事由");
    }

    @Test
    void 不是平台运维不能批借支() {
        long worker = verified("18000000004", "工人", "110101199001011904");
        long outsider = verified("18000000005", "路人", "110101199001011905");
        // 借支是平台垫钱,不是工人自助。少了这条,谁都能给自己批钱
        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, 10_000, "自己批", outsider))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void 金额必须为正() {
        long worker = verified("18000000006", "工人", "110101199001011906");
        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, 0, "零元", ops.userId()))
                .hasMessageContaining("正数");
        assertThatThrownBy(() -> advanceApi.grantAdvance(worker, -5_000, "负数", ops.userId()))
                .hasMessageContaining("正数");
    }

    // ─────────────── 还款 ───────────────

    @Test
    void 线下还款递减未还金额_还清后结清() {
        long worker = verified("18000000007", "工人", "110101199001011907");
        long id = advanceApi.grantAdvance(worker, 50_000, "借 500", ops.userId());

        advanceApi.recordManualRepayment(id, 20_000, ops.userId());
        var mid = advanceApi.findById(id, ops.userId()).orElseThrow();
        assertThat(mid.outstandingCents()).isEqualTo(30_000);
        assertThat(mid.amountCents()).as("本金不该跟着变 —— 还完了还要看得出当初借了多少")
                .isEqualTo(50_000);
        assertThat(mid.status()).isEqualTo("ACTIVE");

        advanceApi.recordManualRepayment(id, 30_000, ops.userId());
        var done = advanceApi.findById(id, ops.userId()).orElseThrow();
        assertThat(done.outstandingCents()).isZero();
        assertThat(done.status()).isEqualTo("CLEARED");
        assertThat(done.clearedAt()).isNotNull();
    }

    @Test
    void 还款超过欠款被拒绝() {
        long worker = verified("18000000008", "工人", "110101199001011908");
        long id = advanceApi.grantAdvance(worker, 10_000, "借 100", ops.userId());
        // 通常是登记时手滑多打一位。让它过去的话数据库 CHECK 会拦下,
        // 但报错是"违反约束",看不出是什么问题
        assertThatThrownBy(() -> advanceApi.recordManualRepayment(id, 10_001, ops.userId()))
                .hasMessageContaining("超过未还");
    }

    @Test
    void 结清后不能再还款() {
        long worker = verified("18000000009", "工人", "110101199001011909");
        long id = advanceApi.grantAdvance(worker, 10_000, "借 100", ops.userId());
        advanceApi.recordManualRepayment(id, 10_000, ops.userId());
        assertThatThrownBy(() -> advanceApi.recordManualRepayment(id, 100, ops.userId()))
                .hasMessageContaining("结清");
    }

    @Test
    void 已还过款的借支不能撤销() {
        long worker = verified("18000000010", "工人", "110101199001011910");
        long id = advanceApi.grantAdvance(worker, 10_000, "借 100", ops.userId());
        advanceApi.recordManualRepayment(id, 1_000, ops.userId());
        // 那些钱已经从工资里扣走了。撤销会让还款记录变成孤儿,账再也对不上
        assertThatThrownBy(() -> advanceApi.cancelAdvance(id, ops.userId()))
                .hasMessageContaining("不能撤销");
    }

    @Test
    void 一分没还的可以撤销() {
        long worker = verified("18000000011", "工人", "110101199001011911");
        long id = advanceApi.grantAdvance(worker, 10_000, "批错了", ops.userId());
        advanceApi.cancelAdvance(id, ops.userId());
        assertThat(advanceApi.findById(id, ops.userId()).orElseThrow().status()).isEqualTo("CANCELLED");
        // 撤销后不再计入欠款,否则额度会被一笔作废的借支永久占住
        assertThat(advanceApi.outstandingOf(worker)).isZero();
    }

    // ─────────────── 工资抵扣(主路径) ───────────────

    @Test
    void 发工资时自动抵扣借支_实发等于应发减借支() {
        Scene s = scene("12", "110101199001011912", "13", "110101199001011913",
                "9111000000000g01X", 100_000L);
        advanceApi.grantAdvance(s.worker, 30_000, "借 300", ops.userId());

        long settlementId = completeAndSettle(s);

        var payout = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).orElse(null),
                        java.util.Objects::nonNull);

        // 应发 100000,借支 30000 → 实发 70000
        assertThat(payout.amountCents())
                .as("实发应当等于应发减去借支")
                .isEqualTo(70_000);
        assertThat(advanceApi.outstandingOf(s.worker)).isZero();
        // 留痕:这笔扣款要查得到是从哪张结算单扣的
        assertThat(repayments.findBySettlementId(settlementId))
                .as("每笔抵扣都要留痕,否则工人问'工资怎么少了'没法回答")
                .hasSize(1);
    }

    @Test
    void 借支多于工资时只扣到工资为止_不产生负工资() {
        Scene s = scene("14", "110101199001011914", "15", "110101199001011915",
                "9111000000000g02X", 20_000L);
        advanceApi.grantAdvance(s.worker, 80_000, "借得比这次工资多", ops.userId());

        long settlementId = completeAndSettle(s);
        var payout = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).orElse(null),
                        java.util.Objects::nonNull);

        // **负工资意味着这个月工人倒欠工厂钱**,不该由抵扣静默产生。
        // 剩下的欠款留到下次工资继续扣
        assertThat(payout.amountCents()).isZero();
        assertThat(advanceApi.outstandingOf(s.worker))
                .as("没扣完的要留着,下次工资接着扣").isEqualTo(60_000);
    }

    @Test
    void 没有借支时工资原样发放() {
        Scene s = scene("16", "110101199001011916", "17", "110101199001011917",
                "9111000000000g03X", 45_000L);
        long settlementId = completeAndSettle(s);
        var payout = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).orElse(null),
                        java.util.Objects::nonNull);
        // 绝大多数人没有借支。这条守的是"接了抵扣之后别把正常发薪弄坏"
        assertThat(payout.amountCents()).isEqualTo(45_000);
    }

    @Test
    void 先借的先还() {
        long worker = verified("18000000018", "工人", "110101199001011918");
        long first  = advanceApi.grantAdvance(worker, 10_000, "第一笔", ops.userId());
        long second = advanceApi.grantAdvance(worker, 10_000, "第二笔", ops.userId());

        advanceApi.recordManualRepayment(first, 10_000, ops.userId());
        assertThat(advanceApi.findById(first, ops.userId()).orElseThrow().status()).isEqualTo("CLEARED");
        assertThat(advanceApi.findById(second, ops.userId()).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void 同一张结算单重复投递不会重复扣钱() {
        Scene s = scene("21", "110101199001011921", "22", "110101199001011922",
                "9111000000000g04X", 60_000L);
        advanceApi.grantAdvance(s.worker, 20_000, "借 200", ops.userId());
        long settlementId = completeAndSettle(s);

        var payout = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).orElse(null),
                        java.util.Objects::nonNull);
        assertThat(payout.amountCents()).isEqualTo(40_000);

        // **中继是至少一次投递,同一条 SettlementCalculated 会重复到达。**
        // 重投一遍,看会不会扣第二次 —— 扣两次的话工人只会看到"钱怎么又少了",
        // 而查不出是被扣了两次。
        //
        // 实测过哪一层在扛(两次实验,做完都还原了):
        //   只停掉监听器里的提前返回      → 这条**照样绿**
        //   连同 settlement_id 一起绕开   → 变红
        // 也就是说真正扛住的是 **advance_repayment 上那条唯一索引**,不是应用层的提前返回。
        // 提前返回只是省掉一次异常;并发重投时两边都会查到"还没扣过",最终由数据库裁决 ——
        // 这正是铁律 4 那句"去重要让数据库裁决,不能靠查一下再写"。
        // 所以**删掉那条索引不会让任何测试变红,但会让重复投递真的扣两次钱**
        var st = settlements.findById(settlementId).orElseThrow();
        events.publishEvent(new com.xbb.settlement.api.SettlementCalculated(
                settlementId, st.getApplicationId(), st.getWorkerUserId(),
                st.getAmountCents(), 0L, java.time.Instant.now()));

        assertThat(repayments.findBySettlementId(settlementId))
                .as("同一张结算单只该扣一次").hasSize(1);
        assertThat(advanceApi.outstandingOf(s.worker))
                .as("重复投递不该把欠款再扣一遍").isZero();
        assertThat(fundApi.findBySettlementId(settlementId).orElseThrow().amountCents())
                .isEqualTo(40_000);
    }

    // ─────────────── 可见性 ───────────────

    @Test
    void 路人看不到别人的借支() {
        long worker = verified("18000000019", "工人", "110101199001011919");
        long outsider = verified("18000000020", "路人", "110101199001011920");
        long id = advanceApi.grantAdvance(worker, 10_000, "借 100", ops.userId());

        // 借支金额说明这个人缺钱 —— 那是不该给无关的人看的(见铁律 5.1)
        assertThat(advanceApi.findById(id, outsider)).isEmpty();
        assertThat(advanceApi.listOf(worker, outsider)).isEmpty();
        assertThat(advanceApi.repaymentsOf(id, outsider)).isEmpty();

        // 正主和平台运维看得到 —— 挡住路人不能连正主一起挡掉
        assertThat(advanceApi.findById(id, worker)).isPresent();
        assertThat(advanceApi.listOf(worker, worker)).hasSize(1);
        assertThat(advanceApi.findById(id, ops.userId())).isPresent();
    }

    // ─────────────── 脚手架 ───────────────

    private record Scene(long boss, long worker, long jobId, long applicationId) { }

    private Scene scene(String bossSuffix, String bossId, String workerSuffix, String workerId,
                        String creditCode, long wageCents) {
        long boss = verified("180000000" + bossSuffix, "老板", bossId);
        long worker = verified("180000000" + workerSuffix, "工人", workerId);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "借支厂" + creditCode, creditCode, boss)));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "借支岗", "描述", wageCents, boss)));
        long jobId = jobH.get();

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> appH.set(engagementApi.apply(jobId, worker)));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss));
        return new Scene(boss, worker, jobId, appId);
    }

    private long completeAndSettle(Scene s) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(s.applicationId(), s.worker(), "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(s.applicationId(), s.boss()));
        return await().atMost(Duration.ofSeconds(25))
                .until(() -> settlements.findByApplicationId(s.applicationId())
                                .map(x -> x.getId()).orElse(null),
                        java.util.Objects::nonNull);
    }
}
