package com.xbb.matching;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.matching.api.MatchingApi;
import com.xbb.matching.internal.JobProjectionRepository;
import com.xbb.matching.internal.WorkerProjectionRepository;
import com.xbb.profile.api.ProfileApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class MatchingServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired ProfileApi profileApi;
    @Autowired MatchingApi matchingApi;
    @Autowired WorkerProjectionRepository workerProjections;
    @Autowired JobProjectionRepository jobProjections;

    // 上海人民广场附近的坐标,用来构造"近"与"远"
    private static final double LAT = 31.2304;
    private static final double LON = 121.4737;

    @BeforeEach
    void cleanProjections() {
        // 匹配是全量扫描候选池,别的测试留下的投影会干扰断言——本域投影是纯派生数据,清掉安全
        jobProjections.deleteAll();
        workerProjections.deleteAll();
    }

    private long registeredUser(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    /** 直接喂画像域,靠事件落到匹配域投影——不手工插投影表,免得测的是假链路。 */
    private long worker(String phone, List<String> tags, long expectedWage, double lat, double lon) {
        long userId = registeredUser(phone);
        profileApi.submitTags(userId, tags);
        profileApi.setWorkerPreference(userId, expectedWage, lat, lon);
        await().atMost(Duration.ofSeconds(5)).until(() -> workerProjections.findById(userId)
                .filter(p -> p.getExpectedWageCents() != null).isPresent());
        return userId;
    }

    private void jobProfile(long jobId, List<String> must, List<String> nice, double lat, double lon) {
        profileApi.setJobProfile(jobId, must, nice, lat, lon);
        await().atMost(Duration.ofSeconds(5)).until(() -> jobProjections.findById(jobId)
                .filter(p -> p.getLat() != null).isPresent());
    }

    @Test
    void 没有投影的用户拿到空推荐而不是报错() {
        assertThat(matchingApi.recommendJobsForWorker(999999L, 5)).isEmpty();
    }

    @Test
    void 缺must标签的岗位被硬约束挡掉不出现在结果里() {
        long userId = worker("15300000010", List.of("普工"), 30000, LAT, LON);
        jobProfile(8001L, List.of("普工"), List.of(), LAT, LON);        // 能报
        jobProfile(8002L, List.of("电工"), List.of(), LAT, LON);        // 没证,报不上

        List<MatchingApi.MatchView> result = matchingApi.recommendJobsForWorker(userId, 10);

        assertThat(result).extracting(MatchingApi.MatchView::targetId).contains(8001L).doesNotContain(8002L);
    }

    @Test
    void 更近更对口的岗位排在前面() {
        long userId = worker("15300000011", List.of("叉车"), 30000, LAT, LON);
        jobProfile(8011L, List.of("叉车"), List.of(), LAT, LON);              // 同点,对口
        jobProfile(8012L, List.of(), List.of("叉车"), LAT + 0.5, LON);        // 远,且只是加分项

        List<MatchingApi.MatchView> result = matchingApi.recommendJobsForWorker(userId, 10);

        assertThat(result.get(0).targetId()).isEqualTo(8011L);
    }

    @Test
    void 推荐结果带可解释理由() {
        long userId = worker("15300000012", List.of("质检"), 30000, LAT, LON);
        jobProfile(8021L, List.of("质检"), List.of(), LAT, LON);

        List<MatchingApi.MatchView> result = matchingApi.recommendJobsForWorker(userId, 5);

        MatchingApi.MatchView top = result.get(0);
        assertThat(top.reason()).isNotBlank();
        // 取贡献最高的两个维度,用 · 连接
        assertThat(top.reason()).contains("·");
    }

    @Test
    void 双边推荐都能跑通() {
        long userId = worker("15300000013", List.of("焊工"), 30000, LAT, LON);
        jobProfile(8031L, List.of("焊工"), List.of(), LAT, LON);

        assertThat(matchingApi.recommendJobsForWorker(userId, 5))
                .extracting(MatchingApi.MatchView::targetId).contains(8031L);
        assertThat(matchingApi.recommendWorkersForJob(8031L, 5))
                .extracting(MatchingApi.MatchView::targetId).contains(userId);
    }

    @Test
    void 探索位给低数据新岗保留固定配额() {
        long userId = worker("15300000014", List.of("普工"), 30000, LAT, LON);
        // 5 个有画像的岗位(高数据)
        for (int i = 0; i < 5; i++) {
            jobProfile(8100L + i, List.of("普工"), List.of(), LAT, LON);
        }
        // 5 个从没设过画像的新岗(低数据):没有标签 → 硬约束天然通过,但技能维度算不出来
        for (int i = 0; i < 5; i++) {
            long jobId = 8200L + i;
            profileApi.setJobProfile(jobId, List.of(), List.of(), LAT, LON);
            await().atMost(Duration.ofSeconds(5)).until(() -> jobProjections.findById(jobId).isPresent());
        }

        List<MatchingApi.MatchView> result = matchingApi.recommendJobsForWorker(userId, 10);

        // ε=0.2,limit=10 → 恰好 2 个探索位。断言配额而不是断言"具体是哪几个"——
        // 后者依赖随机,是 flaky 的根源。
        assertThat(result).hasSize(10);
        assertThat(result.stream().filter(MatchingApi.MatchView::explored)).hasSize(2);
    }
}
