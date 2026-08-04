package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import com.xbb.job.internal.Job;
import com.xbb.matching.api.MatchingApi;
import com.xbb.matching.internal.WorkerProjection;
import com.xbb.matching.internal.WorkerProjectionRepository;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 岗位名额与生命周期(§4.2"发布、生命周期、名额",§5.4 硬约束"名额未满")。
 *
 * <p>此前 {@code Job.Status.CLOSED} 从没被任何代码设置过,名额字段也不存在,
 * 于是招满的岗位会一直被推荐、一直能报名。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class JobLifecycleTest {

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
    @Autowired MatchingApi matchingApi;
    @Autowired WorkerProjectionRepository workerProjections;
    @Autowired com.xbb.job.internal.JobOutboxRepository outbox;
    @Autowired org.springframework.context.ApplicationEventPublisher events;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    private long approvedOrg(long legalRep, String orgName, String creditCode) {
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());
        return orgId;
    }

    private long postJob(long orgId, int headcount, long legalRep) {
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30_000, headcount, legalRep)));
        return jobIdHolder.get();
    }

    private long apply(long jobId, long worker) {
        AtomicLong holder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> holder.set(engagementApi.apply(jobId, worker)));
        return holder.get();
    }

    @Test
    void 单名额岗位录用一人后自动关闭() {
        long legalRep = verifiedUser("16300000001", "法人01", "110101199001029001");
        long orgId = approvedOrg(legalRep, "名额一厂", "91110000000000221X");
        long jobId = postJob(orgId, 1, legalRep);
        long worker = verifiedUser("16300000002", "工人02", "110101199001029002");

        engagementApi.acceptApplication(apply(jobId, worker), legalRep);

        JobApi.JobView view = jobApi.findJob(jobId).orElseThrow();
        assertThat(view.status()).isEqualTo(Job.Status.CLOSED);
        assertThat(view.filledCount()).isEqualTo(1);
        assertThat(view.remainingSlots()).isZero();
    }

    @Test
    void 多名额岗位招满才关闭且超额录用被拒() {
        long legalRep = verifiedUser("16300000003", "法人03", "110101199001029003");
        long orgId = approvedOrg(legalRep, "名额二厂", "91110000000000222X");
        long jobId = postJob(orgId, 2, legalRep);

        long first = verifiedUser("16300000004", "工人04", "110101199001029004");
        long second = verifiedUser("16300000005", "工人05", "110101199001029005");
        long third = verifiedUser("16300000006", "工人06", "110101199001029006");

        // 三个人都先报上名(此时岗位还开着),名额是在录用时才消耗的
        long firstApp = apply(jobId, first);
        long secondApp = apply(jobId, second);
        long thirdApp = apply(jobId, third);

        engagementApi.acceptApplication(firstApp, legalRep);
        assertThat(jobApi.findJob(jobId).orElseThrow().status()).isEqualTo(Job.Status.OPEN);

        engagementApi.acceptApplication(secondApp, legalRep);
        assertThat(jobApi.findJob(jobId).orElseThrow().status()).isEqualTo(Job.Status.CLOSED);

        // 第三个人不能被录进来——同一个坑两个人就是付两份钱
        assertThatThrownBy(() -> engagementApi.acceptApplication(thirdApp, legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
        assertThat(jobApi.findJob(jobId).orElseThrow().filledCount()).isEqualTo(2);
    }

    @Test
    void 岗位关闭后不能再报名() {
        long legalRep = verifiedUser("16300000007", "法人07", "110101199001029007");
        long orgId = approvedOrg(legalRep, "名额三厂", "91110000000000223X");
        long jobId = postJob(orgId, 5, legalRep);
        long worker = verifiedUser("16300000008", "工人08", "110101199001029008");

        jobApi.closeJob(jobId, legalRep);

        // 关闭事件要先流到履约域的投影
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() -> engagementApi.apply(jobId, worker))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("岗位已关闭"));
    }

    @Test
    void 只有法人代表能关闭岗位() {
        long legalRep = verifiedUser("16300000009", "法人09", "110101199001029009");
        long orgId = approvedOrg(legalRep, "名额四厂", "91110000000000224X");
        long jobId = postJob(orgId, 1, legalRep);
        long outsider = verifiedUser("16300000010", "路人10", "110101199001029010");

        assertThatThrownBy(() -> jobApi.closeJob(jobId, outsider))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有组织法人代表");
        assertThat(jobApi.findJob(jobId).orElseThrow().status()).isEqualTo(Job.Status.OPEN);
    }

    @Test
    void 重复关闭不报错也不重复发关闭事件() {
        long legalRep = verifiedUser("16300000011", "法人11", "110101199001029011");
        long orgId = approvedOrg(legalRep, "名额五厂", "91110000000000225X");
        long jobId = postJob(orgId, 3, legalRep);

        jobApi.closeJob(jobId, legalRep);
        jobApi.closeJob(jobId, legalRep);
        jobApi.closeJob(jobId, legalRep);

        assertThat(jobApi.findJob(jobId).orElseThrow().status()).isEqualTo(Job.Status.CLOSED);
        // 重试/重复点击都会走到这里,但下游不该看到同一个岗位关闭三次。
        // 直接数 outbox 行:事件是在关闭那一刻同事务写进去的,数它比等异步投递确定得多。
        assertThat(outbox.findAll().stream()
                .filter(row -> JobClosed.class.getName().equals(row.getEventType()))
                .filter(row -> payloadHasField(row.getPayload(), "jobId", jobId))
                .count()).isEqualTo(1);
    }

    @Test
    void 关闭后的岗位不再出现在推荐里() {
        long legalRep = verifiedUser("16300000012", "法人12", "110101199001029012");
        long orgId = approvedOrg(legalRep, "名额六厂", "91110000000000226X");
        long openJob = postJob(orgId, 3, legalRep);
        long doomedJob = postJob(orgId, 3, legalRep);

        long seeker = 9_500_001L;
        workerProjections.save(new WorkerProjection(seeker, Map.of("普工", 1.0), 30_000L, 31.0, 121.0));

        // 关之前两个都能被推出来
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(matchingApi.recommendJobsForWorker(seeker, 50))
                        .extracting(MatchingApi.MatchView::targetId)
                        .contains(openJob, doomedJob));

        jobApi.closeJob(doomedJob, legalRep);

        // §5.4:硬约束不满足**直接不出现**,不是降权
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(matchingApi.recommendJobsForWorker(seeker, 50))
                        .extracting(MatchingApi.MatchView::targetId)
                        .contains(openJob)
                        .doesNotContain(doomedJob));

        workerProjections.deleteById(seeker);
    }

    /**
     * 投递是至少一次的:JobPosted 会在 JobClosed 之后被重投。
     * 履约域的投影原本是 `save(new PostedJob(...))` 直接覆盖整行,
     * 那样 open 会被重置回 true——**关掉的岗位复活,工人又能报名进来**。
     */
    @Test
    void 重投的岗位发布事件不会让已关闭的岗位复活() {
        long legalRep = verifiedUser("16300000013", "法人13", "110101199001029013");
        long orgId = approvedOrg(legalRep, "名额七厂", "91110000000000227X");
        long jobId = postJob(orgId, 5, legalRep);
        long worker = verifiedUser("16300000014", "工人14", "110101199001029014");

        jobApi.closeJob(jobId, legalRep);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() -> engagementApi.apply(jobId, worker))
                        .hasMessageContaining("岗位已关闭"));

        // 模拟中继重投:同一条 JobPosted 再发一次
        events.publishEvent(new JobPosted(jobId, orgId, 30_000, 5, java.time.Instant.now()));

        assertThatThrownBy(() -> engagementApi.apply(jobId, worker))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("岗位已关闭");
    }

    /**
     * JSON 字段精确匹配。
     *
     * <p>原来直接用 contains("\"settlementId\":" + id):id 是 1 时会匹配到 12、13……
     * 全新的隔离库里 id 都是小整数,正好踩中。这个 bug 表现为"单独跑过、全量跑挂",
     * 排查了很久才发现问题在测试辅助方法里,不在被测代码。
     */
    private static boolean payloadHasField(String payload, String field, long value) {
        return payload.matches(".*\"" + field + "\"\\s*:\\s*" + value + "\\s*[,}].*");
    }
}
