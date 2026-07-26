package com.xbb.matching;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.matching.api.MatchingApi;
import com.xbb.matching.internal.JobProjection;
import com.xbb.matching.internal.JobProjectionRepository;
import com.xbb.matching.internal.WorkerProjection;
import com.xbb.matching.internal.WorkerProjectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匹配候选池上限(批次 4.2)。
 *
 * <p>硬约束(must 标签的子集判断)目前仍在内存里做,所以挡住"一次推荐扫全表"的
 * 只有这个上限。上限生效时留下谁是策略:这里钉死"最近更新的岗位优先"。
 */
@SpringBootTest(properties = "xbb.matching.candidate-pool-limit=2")
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class MatchingPoolLimitTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MatchingApi matchingApi;
    @Autowired JobProjectionRepository jobs;
    @Autowired WorkerProjectionRepository workers;

    private ListAppender<ILoggingEvent> logs;
    private Logger matchingLogger;
    private final List<Long> seededJobs = new ArrayList<>();
    private Long seededWorker;

    @BeforeEach
    void 接住日志() {
        matchingLogger = (Logger) LoggerFactory.getLogger("com.xbb.matching.internal.MatchingService");
        logs = new ListAppender<>();
        logs.start();
        matchingLogger.addAppender(logs);
    }

    @AfterEach
    void 收尾() {
        matchingLogger.detachAppender(logs);
        seededJobs.forEach(jobs::deleteById);
        if (seededWorker != null) {
            workers.deleteById(seededWorker);
        }
    }

    /** 顺序写入,后写的 updatedAt 更晚——"最近更新优先"要的就是这个顺序。 */
    private void seedJob(long jobId) {
        JobProjection job = new JobProjection(jobId, 9_400_000L, 30_000L);
        job.updateProfile(List.of(), List.of(), 31.0, 121.0);
        jobs.save(job);
        seededJobs.add(jobId);
    }

    @Test
    void 候选池超上限时保留最近更新的岗位并留下告警() {
        long workerId = 9_400_101L;
        workers.save(new WorkerProjection(workerId, Map.of("普工", 1.0), 30_000L, 31.0, 121.0));
        seededWorker = workerId;

        seedJob(9_400_001L);   // 最旧
        seedJob(9_400_002L);
        seedJob(9_400_003L);   // 最新

        List<MatchingApi.MatchView> recommended = matchingApi.recommendJobsForWorker(workerId, 10);

        // 上限 2:最旧的那个岗位连进候选池的机会都没有
        assertThat(recommended).extracting(MatchingApi.MatchView::targetId)
                .containsExactlyInAnyOrder(9_400_003L, 9_400_002L)
                .doesNotContain(9_400_001L);

        // 静默截断会让"我的岗位没被推出去"看起来像打分逻辑判错,排查方向从一开始就是歪的
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("候选池达到上限") && m.contains("被截断"));
    }
}
