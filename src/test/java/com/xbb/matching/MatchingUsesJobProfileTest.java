package com.xbb.matching;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.profile.api.ProfileApi;
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
import static org.awaitility.Awaitility.await;

/**
 * 岗位画像填了之后,撮合真的会用上它。
 *
 * <p><b>为什么要有这一条。</b>2026-08-07 审计发现:写画像的那个端点
 * 一直没有任何界面入口,所以从来没人填过 —— {@code MatchScorer} 拿到的
 * {@code mustTags} 是空的、坐标是 null,于是撮合**静默退化成只按薪资和信用排序**,
 * 而界面上完全看不出来。
 *
 * <p>这条守的不是"画像能存进去"(那个早就能),而是**存进去之后撮合的结果真的变了**。
 * 只验存取的话,中间那段副本链路断掉也照样绿。
 *
 * <p>号段 13006,信用代码 …r xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class MatchingUsesJobProfileTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired ProfileApi profileApi;
    @Autowired com.xbb.matching.api.MatchingApi matchingApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    @Test
    void 填了必备技能之后_不具备该技能的人被挡在推荐之外() {
        long boss = verified("13006000001", "厂长", "110101199001130001");
        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "画像厂", "9111000000000r01X", boss)));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "焊工岗", "要会焊", 3000, boss)));
        long jobId = jobH.get();

        long welder = verified("13006000002", "会焊的", "110101199001130002");
        long packer = verified("13006000003", "只会打包的", "110101199001130003");
        profileApi.submitTags(welder, List.of("焊工"));
        profileApi.submitTags(packer, List.of("打包"));

        // **填画像之前**:必备技能为空,谁都能进推荐 ——
        // 这正是审计发现的那个状态
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(matchingApi.recommendWorkersForJob(jobId, 10))
                        .as("没填画像时不该把人挡掉")
                        .isNotEmpty());

        // 填上「焊工」这个必备技能
        profileApi.setJobProfile(jobId, List.of("焊工"), List.of(), 31.2989, 120.5853, boss);

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            var ids = matchingApi.recommendWorkersForJob(jobId, 10).stream()
                    .map(w -> w.targetId()).toList();
            // **这条是全部要害。**画像存进去了但撮合没变的话,
            // 说明中间那段副本链路是断的 —— 而界面上看不出来
            assertThat(ids).as("会焊的人应该被推荐").contains(welder);
            assertThat(ids).as("不会焊的人应该被必备技能挡掉").doesNotContain(packer);
        });
    }
}
