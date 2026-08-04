package com.xbb.review;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.ops.api.OpsApi;
import com.xbb.review.api.ReviewApi;
import com.xbb.review.internal.CompletedEngagementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class ReviewServiceTest {

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
    @Autowired ReviewApi reviewApi;
    @Autowired OpsApi opsApi;
    @Autowired CompletedEngagementRepository completedEngagements;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    /** 走完整链路直到履约完成,返回 [applicationId, legalRepId, workerId]。 */
    private long[] completedEngagement(String legalRepPhone, String legalRepId,
                                        String workerPhone, String workerId,
                                        String orgName, String creditCode) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();

        long worker = verifiedUser(workerPhone, "工人" + workerPhone.substring(8), workerId);
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2),没签不让完成
        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(applicationId, worker, "SMS"));
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep));

        await().atMost(Duration.ofSeconds(15)).until(() -> completedEngagements.findById(applicationId).isPresent());
        return new long[]{applicationId, legalRep, worker};
    }

    // ---------- R1 强绑履约(§5.3) ----------

    @Test
    void 没完成的履约单不能评价() {
        assertThatThrownBy(() -> reviewApi.submitReview(999999L, 1L, List.of("准时到岗"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已完成");
    }

    @Test
    void 同一履约单同一人只能评一次() {
        long[] ids = completedEngagement("15500000001", "110101199001018001",
                "15500000002", "110101199001018002", "评价一厂", "91110000000000111X");
        reviewApi.submitReview(ids[0], ids[1], List.of("准时到岗"), "干得不错");

        assertThatThrownBy(() -> reviewApi.submitReview(ids[0], ids[1], List.of("态度好"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只能评价一次");
    }

    // ---------- R2 双盲(§5.3) ----------

    @Test
    void 只有一方提交时评价不公开() {
        long[] ids = completedEngagement("15500000003", "110101199001018003",
                "15500000004", "110101199001018004", "评价二厂", "91110000000000112X");

        reviewApi.submitReview(ids[0], ids[1], List.of("准时到岗"), "好");

        // 掐掉报复性差评:对方没提交、7 天没到,谁也看不到
        assertThat(reviewApi.findVisibleReviews(ids[0], ops.userId())).isEmpty();
    }

    @Test
    void 双方都提交后同时公开() {
        long[] ids = completedEngagement("15500000005", "110101199001018005",
                "15500000006", "110101199001018006", "评价三厂", "91110000000000113X");

        reviewApi.submitReview(ids[0], ids[1], List.of("准时到岗"), "好");
        reviewApi.submitReview(ids[0], ids[2], List.of("结算准时"), "老板不错");

        assertThat(reviewApi.findVisibleReviews(ids[0], ops.userId())).hasSize(2);
    }

    // ---------- 标签方向校验 ----------

    @Test
    void 工人不能用工厂侧标签评价() {
        long[] ids = completedEngagement("15500000007", "110101199001018007",
                "15500000008", "110101199001018008", "评价四厂", "91110000000000114X");

        // 工人只能用"结算准时/拖欠工资"这类工厂侧标签,不能用"中途跑单"评工厂
        assertThatThrownBy(() -> reviewApi.submitReview(ids[0], ids[2], List.of("中途跑单"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该评价方向");
    }

    // ---------- 信用分(§5.3.2) ----------

    @Test
    void 履约完成后工人有了信用分() {
        long[] ids = completedEngagement("15500000009", "110101199001018009",
                "15500000010", "110101199001018010", "评价五厂", "91110000000000115X");

        ReviewApi.CreditView credit = reviewApi.findCreditScore(ids[2]).orElseThrow();

        assertThat(credit.score()).isBetween(0.0, 100.0);
        assertThat(credit.tier()).isNotBlank();
    }

    @Test
    void 差评拉低工人信用分() {
        long[] good = completedEngagement("15500000011", "110101199001018011",
                "15500000012", "110101199001018012", "评价六厂", "91110000000000116X");
        long[] bad = completedEngagement("15500000013", "110101199001018013",
                "15500000014", "110101199001018014", "评价七厂", "91110000000000117X");

        reviewApi.submitReview(good[0], good[1], List.of("准时到岗", "手脚麻利"), null);
        reviewApi.submitReview(bad[0], bad[1], List.of("中途跑单"), null);

        double goodScore = reviewApi.findCreditScore(good[2]).orElseThrow().score();
        double badScore = reviewApi.findCreditScore(bad[2]).orElseThrow().score();

        assertThat(badScore).isLessThan(goodScore);
    }

    /**
     * 运营调权重是**向前生效**的:已经落库的评价分不能被追溯改写。
     * 否则一次词表调整就会把全站历史评分和依赖它的信用分一起改掉,没人能解释分数为什么变了。
     */
    @Test
    void 运营调整权重后老评价的分数不被追溯改写() {
        long[] ids = completedEngagement("15500000015", "110101199001018015",
                "15500000016", "110101199001018016", "评价八厂", "91110000000000118X");
        reviewApi.submitReview(ids[0], ids[1], List.of("迟到早退"), "偶尔迟到");
        reviewApi.submitReview(ids[0], ids[2], List.of("结算准时"), "还行");

        double before = reviewApi.findVisibleReviews(ids[0], ops.userId()).stream()
                .filter(r -> r.raterUserId() == ids[1]).findFirst().orElseThrow().score();
        assertThat(before).isCloseTo(4.5, within(1e-9));

        long light = opsApi.findItem(OpsApi.REVIEW_SEVERITY_WEIGHT, "LIGHT").orElseThrow().id();
        try {
            opsApi.updateValue(light, "3.0");

            double after = reviewApi.findVisibleReviews(ids[0], ops.userId()).stream()
                    .filter(r -> r.raterUserId() == ids[1]).findFirst().orElseThrow().score();
            assertThat(after).isCloseTo(before, within(1e-9));
        } finally {
            opsApi.updateValue(light, "0.5");
        }
    }
}
