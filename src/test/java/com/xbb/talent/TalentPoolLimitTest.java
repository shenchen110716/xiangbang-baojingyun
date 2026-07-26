package com.xbb.talent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.talent.api.TalentApi;
import com.xbb.talent.internal.TalentProfile;
import com.xbb.talent.internal.TalentProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选池上限(批次 4.2/4.3)。
 *
 * <p>要验的不只是"有没有截断",更是**截断时留下了谁**:上限一旦生效,
 * 取哪些行就从实现细节变成了产品策略。这里把策略钉死成
 * "履约次数多、最近活跃的优先",并要求截断必须留下日志。
 *
 * <p>用例自己清理造出来的档案:上限只有 2,留在共享库里的高次数档案
 * 会把同类用例互相挤出候选池,那种失败极难排查。
 */
@SpringBootTest(properties = "xbb.talent.candidate-pool-limit=2")
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class TalentPoolLimitTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    private static final String TAG = "限流专用标签";

    @Autowired TalentApi talentApi;
    @Autowired TalentProfileRepository profiles;

    private ListAppender<ILoggingEvent> logs;
    private Logger talentLogger;
    private final List<Long> seeded = new ArrayList<>();

    @BeforeEach
    void 接住日志() {
        talentLogger = (Logger) LoggerFactory.getLogger("com.xbb.talent.internal.TalentService");
        logs = new ListAppender<>();
        logs.start();
        talentLogger.addAppender(logs);
    }

    @AfterEach
    void 收尾() {
        talentLogger.detachAppender(logs);
        seeded.forEach(profiles::deleteById);
    }

    /** 直接落档案:这里要的是精确的履约次数排序,走完整履约流程既做不到也没必要。 */
    private void seedProfile(long userId, int completedEngagements) {
        TalentProfile profile = new TalentProfile(userId);
        profile.updateProfile(Map.of(TAG, 1.0), 30_000L);
        for (int i = 0; i < completedEngagements; i++) {
            profile.recordEngagementCompleted(Instant.now());
        }
        profiles.save(profile);
        seeded.add(userId);
    }

    private boolean 截断被告警() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("候选池达到上限") && m.contains("被截断"));
    }

    @Test
    void 候选池超上限时按履约次数保留前两名并留下告警() {
        // 次数取得远高于其它用例造的数据,保证全局排序里这三条就是前三
        seedProfile(9_310_001L, 900);
        seedProfile(9_310_002L, 899);
        seedProfile(9_310_003L, 898);

        List<TalentApi.TalentView> found = talentApi.search(List.of(TAG), 10);

        // 上限 2:第三个人标签明明命中,也进不了候选池
        assertThat(found).extracting(TalentApi.TalentView::userId)
                .containsExactly(9_310_001L, 9_310_002L)
                .doesNotContain(9_310_003L);

        // 不能静默截断——否则"人没搜出来"会被当成标签没配对去排查
        assertThat(截断被告警()).isTrue();
    }

    /**
     * 决定谁被留下的是排序策略,不是数据库随手给的顺序。
     * 同一个人,只是履约次数涨上去,就从"被截掉"变成"排第一"。
     */
    @Test
    void 履约次数涨上去之后原本被截掉的人进入候选池() {
        seedProfile(9_310_011L, 920);
        seedProfile(9_310_012L, 919);
        seedProfile(9_310_013L, 900);

        assertThat(talentApi.search(List.of(TAG), 10))
                .extracting(TalentApi.TalentView::userId)
                .doesNotContain(9_310_013L);

        TalentProfile promoted = profiles.findById(9_310_013L).orElseThrow();
        for (int i = 0; i < 30; i++) {
            promoted.recordEngagementCompleted(Instant.now());   // 900 → 930,升到全局第一
        }
        profiles.save(promoted);

        assertThat(talentApi.search(List.of(TAG), 10))
                .extracting(TalentApi.TalentView::userId)
                .startsWith(9_310_013L);
    }
}
