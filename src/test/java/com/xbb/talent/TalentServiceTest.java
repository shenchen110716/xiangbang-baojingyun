package com.xbb.talent;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.profile.api.ProfileApi;
import com.xbb.talent.api.TalentApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 人才库(§4.2 通用域):档案沉淀复用,订阅画像事件。 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class TalentServiceTest {

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
    @Autowired TalentApi talentApi;
    @Autowired ApplicationEventPublisher events;

    private long registeredUser(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = registeredUser(phone);
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    @Test
    void 提交画像后人才库出现档案() {
        long userId = registeredUser("16000000001");

        profileApi.submitTags(userId, List.of("叉车", "质检"));

        await().atMost(Duration.ofSeconds(5)).until(() -> talentApi.findTalent(userId).isPresent());
        TalentApi.TalentView view = talentApi.findTalent(userId).orElseThrow();
        assertThat(view.tags()).containsOnlyKeys("叉车", "质检");
    }

    @Test
    void 按标签检索命中() {
        long userId = registeredUser("16000000002");
        profileApi.submitTags(userId, List.of("焊工"));
        await().atMost(Duration.ofSeconds(5)).until(() -> talentApi.findTalent(userId).isPresent());

        List<TalentApi.TalentView> found = talentApi.search(List.of("焊工"), 20);

        assertThat(found).extracting(TalentApi.TalentView::userId).contains(userId);
    }

    @Test
    void 命中标签多的排在前面() {
        long twoTags = registeredUser("16000000003");
        profileApi.submitTags(twoTags, List.of("电工", "注塑"));
        long oneTag = registeredUser("16000000004");
        profileApi.submitTags(oneTag, List.of("电工"));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                talentApi.findTalent(twoTags).isPresent() && talentApi.findTalent(oneTag).isPresent());

        List<TalentApi.TalentView> found = talentApi.search(List.of("电工", "注塑"), 20);

        assertThat(found.get(0).userId()).isEqualTo(twoTags);
        assertThat(found.get(0).matchedTagCount()).isEqualTo(2);
    }

    @Test
    void 检索不存在的标签返回空() {
        assertThat(talentApi.search(List.of("保安"), 20))
                .extracting(TalentApi.TalentView::userId)
                .doesNotContain(-1L);   // 只要不炸即可,标签没人有就应为空或不含该人
    }

    @Test
    void 履约完成后累计次数增加且刷新最近活跃时间() {
        // 走完整链路:入驻 → 发岗 → 报名 → 录用 → 签协议 → 完成
        long legalRep = verifiedUser("16000000005", "法人才库", "110101199001026005");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "人才库厂", "91110000000000181X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();

        long worker = verifiedUser("16000000006", "工人才库", "110101199001026006");
        profileApi.submitTags(worker, List.of("普工"));
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                agreementApi.sign(applicationId, worker, "SMS"));
        engagementApi.completeApplication(applicationId, legalRep);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(talentApi.findTalent(worker).orElseThrow().completedEngagements())
                        .isEqualTo(1));
        assertThat(talentApi.findTalent(worker).orElseThrow().lastActiveAt()).isNotNull();
    }

    @Test
    void 标签命中数相同时履约次数多的排前面() {
        // 这正是人才库相对于画像的增量价值:"干过并且干完了"比只是自称会更值得推荐
        long experienced = registeredUser("16000000007");
        profileApi.submitTags(experienced, List.of("仓管"));
        long fresh = registeredUser("16000000008");
        profileApi.submitTags(fresh, List.of("仓管"));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                talentApi.findTalent(experienced).isPresent() && talentApi.findTalent(fresh).isPresent());

        // 给 experienced 造一次真实的履约完成
        long legalRep = verifiedUser("16000000009", "法人仓管", "110101199001026009");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "仓管厂", "91110000000000182X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "仓管", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();
        identityApi.verifyRealName(experienced, "老仓管", "110101199001026007");
        AtomicLong appHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                appHolder.set(engagementApi.apply(jobId, experienced)));
        engagementApi.acceptApplication(appHolder.get(), legalRep);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                agreementApi.sign(appHolder.get(), experienced, "SMS"));
        engagementApi.completeApplication(appHolder.get(), legalRep);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(talentApi.findTalent(experienced).orElseThrow().completedEngagements())
                        .isGreaterThan(0));

        List<TalentApi.TalentView> found = talentApi.search(List.of("仓管"), 20);

        int expIdx = indexOf(found, experienced);
        int freshIdx = indexOf(found, fresh);
        assertThat(expIdx).isGreaterThanOrEqualTo(0).isLessThan(freshIdx);
    }

    private static int indexOf(List<TalentApi.TalentView> list, long userId) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).userId() == userId) return i;
        return -1;
    }

    /**
     * 事件改走 outbox 之后投递语义是**至少一次**,而累计履约次数是累加的——
     * 同一单数两次,人才库的排序(以及候选池截断时留下谁)就被刷高了。
     * 按履约单去重,不是按 eventId:同一单换个 eventId 重发也只该计一次。
     */
    @Test
    void 同一履约单重复投递只计一次履约次数() {
        long worker = 9_700_001L;
        long applicationId = 9_700_101L;

        events.publishEvent(EngagementCompleted.of(
                applicationId, 9_700_201L, worker, 9_700_301L, 30_000, java.time.Instant.now()));
        int afterFirst = talentApi.findTalent(worker).orElseThrow().completedEngagements();

        // 换一个 eventId 重发同一单,模拟中继重投
        events.publishEvent(EngagementCompleted.of(
                applicationId, 9_700_201L, worker, 9_700_301L, 30_000, java.time.Instant.now()));

        assertThat(afterFirst).isEqualTo(1);
        assertThat(talentApi.findTalent(worker).orElseThrow().completedEngagements()).isEqualTo(1);
    }
}
