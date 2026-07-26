package com.xbb.matching;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.matching.internal.JobProjection;
import com.xbb.matching.internal.JobProjectionRepository;
import com.xbb.matching.internal.WorkerProjection;
import com.xbb.matching.internal.WorkerProjectionRepository;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
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

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ProjectionTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AgreementApi agreementApi;
    @Autowired ProfileApi profileApi;
    @Autowired WorkerProjectionRepository workerProjections;
    @Autowired JobProjectionRepository jobProjections;

    @Test
    void 人才标签与偏好被匹配域投影() {
        String phone = "15300000001";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        profileApi.submitTags(userId, List.of("普工", "叉车"));
        profileApi.setWorkerPreference(userId, 30000, 31.2304, 121.4737);

        await().atMost(Duration.ofSeconds(5)).until(() -> workerProjections.findById(userId)
                .filter(p -> p.getExpectedWageCents() != null).isPresent());
        WorkerProjection projection = workerProjections.findById(userId).orElseThrow();
        assertThat(projection.getTags()).containsOnlyKeys("普工", "叉车");
        assertThat(projection.getTags().get("普工")).isEqualTo(0.4);
        assertThat(projection.getExpectedWageCents()).isEqualTo(30000);
        assertThat(projection.getLat()).isEqualTo(31.2304);
    }

    @Test
    void 只提交标签没填偏好时投影里的薪资与坐标为空() {
        String phone = "15300000002";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        profileApi.submitTags(userId, List.of("质检"));

        await().atMost(Duration.ofSeconds(5)).until(() -> workerProjections.findById(userId).isPresent());
        WorkerProjection projection = workerProjections.findById(userId).orElseThrow();
        assertThat(projection.getTags()).containsOnlyKeys("质检");
        // 维度缺失是正常的冷启动状态,不是错误——评分函数必须容忍
        assertThat(projection.getExpectedWageCents()).isNull();
        assertThat(projection.getLat()).isNull();
    }

    @Test
    void 信用分变更被匹配域投影() {
        String legalRepPhone = "15700000001";
        long legalRep = identityApi.loginByPhone(legalRepPhone, codes.issue(legalRepPhone)).userId();
        identityApi.verifyRealName(legalRep, "法人信用", "110101199001022001");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "信用测试厂", "91110000000000131X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();

        String workerPhone = "15700000002";
        long worker = identityApi.loginByPhone(workerPhone, codes.issue(workerPhone)).userId();
        identityApi.verifyRealName(worker, "工人信用", "110101199001022002");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2),没签不让完成
        agreementApi.sign(applicationId, worker, "SMS");
        engagementApi.completeApplication(applicationId, legalRep);

        // 履约完成 → 评价域算信用分 → CreditScoreChanged → 匹配域投影
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workerProjections.findById(worker).orElseThrow().getCreditScore()).isNotNull());
        assertThat(workerProjections.findById(worker).orElseThrow().getCreditScore()).isBetween(0.0, 100.0);
    }

    @Test
    void 岗位发布与岗位画像共同构成岗位投影() {
        String phone = "15300000003";
        long legalRep = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(legalRep, "周法人", "110101199001016001");

        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "匹配测试工厂", "91110000000000091X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "叉车工", "仓库叉车", 32000, legalRep)));
        long jobId = jobIdHolder.get();

        // JobPosted 先落地基本信息
        await().atMost(Duration.ofSeconds(5)).until(() -> jobProjections.findById(jobId).isPresent());
        assertThat(jobProjections.findById(jobId).orElseThrow().getWageCents()).isEqualTo(32000);

        // JobProfileUpdated 再补上标签与坐标,不覆盖薪资
        profileApi.setJobProfile(jobId, List.of("叉车"), List.of("普工"), 31.2304, 121.4737);

        await().atMost(Duration.ofSeconds(5)).until(() -> jobProjections.findById(jobId)
                .filter(p -> p.getLat() != null).isPresent());
        JobProjection projection = jobProjections.findById(jobId).orElseThrow();
        assertThat(projection.getMustTags()).containsExactly("叉车");
        assertThat(projection.getNiceTags()).containsExactly("普工");
        assertThat(projection.getLat()).isEqualTo(31.2304);
        assertThat(projection.getWageCents()).isEqualTo(32000);  // 画像更新没有把薪资冲掉
        assertThat(projection.getOrgId()).isEqualTo(orgId);
    }
}
