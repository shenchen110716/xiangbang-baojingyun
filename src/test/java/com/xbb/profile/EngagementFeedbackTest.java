package com.xbb.profile;

import com.xbb.TestcontainersConfig;
import com.xbb.profile.TestJobOwner;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
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

/**
 * 履约反哺画像(§5.2"这个模块的灵魂"):
 * "他说他会,不算数;他干过并且评价好,才算数。"
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class, TestJobOwner.class})
class EngagementFeedbackTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestJobOwner.Accessor jobOwner;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AgreementApi agreementApi;
    @Autowired ProfileApi profileApi;
    @Autowired WorkerProjectionRepository workerProjections;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    /** 走到履约完成,返回 workerUserId。岗位画像的 must/nice 由调用方指定。 */
    private long completeEngagementOn(String legalRepPhone, String legalRepId, String workerPhone,
                                       String workerIdNumber, String orgName, String creditCode,
                                       List<String> jobMust, List<String> jobNice,
                                       List<String> workerTags) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "岗位", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();
        profileApi.setJobProfile(jobId, jobMust, jobNice, 31.0, 121.0, jobOwner.of(jobId));

        long worker = verifiedUser(workerPhone, "工人" + workerPhone.substring(8), workerIdNumber);
        profileApi.submitTags(worker, workerTags);

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
        return worker;
    }

    @Test
    void 干过的岗位对应标签从自述升级为履约验证() {
        long worker = completeEngagementOn("15600000001", "110101199001023001",
                "15600000002", "110101199001023002", "反哺一厂", "91110000000000121X",
                List.of("叉车"), List.of(), List.of("叉车"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ProfileApi.ProfileTagView tag = profileApi.getProfile(worker).stream()
                    .filter(t -> t.tagName().equals("叉车")).findFirst().orElseThrow();
            assertThat(tag.source()).isEqualTo("ENGAGEMENT_VERIFIED");
            assertThat(tag.confidence()).isEqualTo(1.0);
        });
    }

    @Test
    void 只升级与岗位相交的标签不相干的自述标签不动() {
        // 他去干了叉车岗,能证明的是"叉车",不能证明他自称的"电工"
        long worker = completeEngagementOn("15600000003", "110101199001023003",
                "15600000004", "110101199001023004", "反哺二厂", "91110000000000122X",
                List.of("叉车"), List.of(), List.of("叉车", "电工"));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<ProfileApi.ProfileTagView> profile = profileApi.getProfile(worker);
            assertThat(profile).filteredOn(t -> t.tagName().equals("叉车"))
                    .allSatisfy(t -> assertThat(t.source()).isEqualTo("ENGAGEMENT_VERIFIED"));
            assertThat(profile).filteredOn(t -> t.tagName().equals("电工"))
                    .allSatisfy(t -> assertThat(t.source()).isEqualTo("SELF_REPORTED"));
        });
    }

    @Test
    void 反哺后匹配域投影里的置信度同步更新() {
        long worker = completeEngagementOn("15600000005", "110101199001023005",
                "15600000006", "110101199001023006", "反哺三厂", "91110000000000123X",
                List.of("质检"), List.of(), List.of("质检"));

        // 置信度变了下游必须知道,否则技能维度还在用旧的 0.4 算
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(workerProjections.findById(worker).orElseThrow().getTags().get("质检"))
                        .isEqualTo(1.0));
    }

    @Test
    void 反哺不会把工人已填的期望薪资与坐标清空() {
        long legalRep = verifiedUser("15600000007", "法人反四", "110101199001023007");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "反哺四厂", "91110000000000124X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "岗位", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();
        profileApi.setJobProfile(jobId, List.of("焊工"), List.of(), 31.0, 121.0, jobOwner.of(jobId));

        long worker = verifiedUser("15600000008", "工人反四", "110101199001023008");
        profileApi.submitTags(worker, List.of("焊工"));
        profileApi.setWorkerPreference(worker, 42000, 31.5, 121.5);

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

        // 反哺发的 ProfileUpdated 是整行覆盖投影的,载荷里漏带偏好会静默清空已填数据
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(workerProjections.findById(worker).orElseThrow().getTags().get("焊工")).isEqualTo(1.0));
        assertThat(workerProjections.findById(worker).orElseThrow().getExpectedWageCents()).isEqualTo(42000);
        assertThat(workerProjections.findById(worker).orElseThrow().getLat()).isEqualTo(31.5);
    }
}
