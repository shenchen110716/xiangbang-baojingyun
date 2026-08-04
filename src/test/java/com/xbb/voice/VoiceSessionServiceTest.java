package com.xbb.voice;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.job.internal.ApprovedOrgRepository;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.voice.api.VoiceApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, VoiceSessionServiceTest.MovableClockConfig.class, TestPlatformOps.class})
class VoiceSessionServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    /**
     * 可推进的时钟:撤回窗口是 5 分钟,测试不能真的 sleep 五分钟,
     * 也不能靠"跑得快所以没超时"这种隐式假设。
     */
    static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-07-26T00:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class MovableClockConfig {
        // 不能和生产的 bean 同名(会触发 BeanDefinitionOverrideException),
        // 靠 @Primary 决定注入优先级。
        @Bean @Primary
        Clock testMovableClock() { return new MovableClock(); }
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    /** 用来确认 job 域的组织副本真的落地了,见 approvedOrgWithLegalRep 的注释。 */
    @Autowired ApprovedOrgRepository jobApprovedOrgs;
    @Autowired VoiceApi voiceApi;
    @Autowired Clock clock;

    private long approvedOrgWithLegalRep(String phone, String idNumber, String orgName,
                                          String creditCode, AtomicLong legalRepOut) {
        long legalRep = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(legalRep, "法人" + phone.substring(8), idNumber);
        legalRepOut.set(legalRep);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());
        // job 域的已审核组织副本是异步落地的,等它出现再发单。
        //
        // **直接查副本表,不要拿 checkWageAnomaly 当探针。**原来那句
        // `untilAsserted(() -> jobApi.checkWageAnomaly(orgId, 20_000))` 是个空操作:
        // checkWageAnomaly 只读 jobs 表、压根不碰 approvedOrgs,副本没到它也不会抛,
        // 于是 await 立刻通过,后面发单才撞上"组织未通过审核"。
        // 症状是这个类里**随机某一条**测试挂掉 —— 哪条先撞上竞态就是哪条,
        // 看起来像偶发抖动,其实是等待条件从来没生效过。
        await().atMost(Duration.ofSeconds(20))
                .until(() -> jobApprovedOrgs.findById(orgId).isPresent());
        return orgId;
    }

    @Test
    void 起草后回读用口语化数字() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000001", "110101199001025001",
                "语音一厂", "91110000000000171X", legalRep);

        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "普工", 20, 20_000, "包吃住");

        assertThat(draft.readback()).contains("二十个普工").contains("两百块一天");
        assertThat(draft.hasAnomaly()).isFalse();
    }

    @Test
    void 明确肯定才真的发布岗位() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000002", "110101199001025002",
                "语音二厂", "91110000000000172X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "质检", 5, 25_000, null);

        VoiceApi.ConfirmResult result = voiceApi.confirm(draft.sessionId(), legalRep.get(), "对");

        assertThat(result.published()).isTrue();
        assertThat(jobApi.findJob(result.jobId())).isPresent();
    }

    @Test
    void 模糊应答不发布而是重新回读() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000003", "110101199001025003",
                "语音三厂", "91110000000000173X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "打包", 3, 22_000, null);

        VoiceApi.ConfirmResult result = voiceApi.confirm(draft.sessionId(), legalRep.get(), "嗯");

        assertThat(result.published()).isFalse();
        assertThat(result.ambiguous()).isTrue();
        assertThat(result.reply()).contains("再说一遍").contains("三个打包");
    }

    @Test
    void 明确否定进入修改不发布() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000004", "110101199001025004",
                "语音四厂", "91110000000000174X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "分拣", 4, 23_000, null);

        VoiceApi.ConfirmResult result = voiceApi.confirm(draft.sessionId(), legalRep.get(), "不对");

        assertThat(result.published()).isFalse();
        assertThat(result.ambiguous()).isFalse();
    }

    @Test
    void 离谱薪资触发质疑并写进回读() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000005", "110101199001025005",
                "语音五厂", "91110000000000175X", legalRep);
        // 先发几单正常价建立历史
        voiceApi.confirm(voiceApi.draftJob(legalRep.get(), orgId, "普工", 10, 20_000, null).sessionId(),
                legalRep.get(), "对");
        voiceApi.confirm(voiceApi.draftJob(legalRep.get(), orgId, "普工", 10, 21_000, null).sessionId(),
                legalRep.get(), "对");

        // §5.1:"日薪 2000 的普工岗系统必须起疑"
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "普工", 20, 200_000, null);

        assertThat(draft.hasAnomaly()).isTrue();
        assertThat(draft.readback()).contains("两千块一天").contains("确定吗");
    }

    // ---------- 撤回窗口(§5.1 防线③) ----------

    @Test
    void 五分钟内可以撤回() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000006", "110101199001025006",
                "语音六厂", "91110000000000176X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "理货", 2, 24_000, null);
        VoiceApi.ConfirmResult published = voiceApi.confirm(draft.sessionId(), legalRep.get(), "对");
        assertThat(published.published()).isTrue();

        ((MovableClock) clock).advance(Duration.ofMinutes(4));
        VoiceApi.ConfirmResult recalled = voiceApi.recall(draft.sessionId(), legalRep.get());

        assertThat(recalled.reply()).contains("已撤回");
    }

    @Test
    void 超过五分钟不能撤回() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000007", "110101199001025007",
                "语音七厂", "91110000000000177X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "搬运", 2, 26_000, null);
        voiceApi.confirm(draft.sessionId(), legalRep.get(), "对");

        ((MovableClock) clock).advance(Duration.ofMinutes(6));
        VoiceApi.ConfirmResult recalled = voiceApi.recall(draft.sessionId(), legalRep.get());

        assertThat(recalled.reply()).contains("超过五分钟");
    }

    @Test
    void 不能操作别人的语音会话() {
        AtomicLong legalRep = new AtomicLong();
        long orgId = approvedOrgWithLegalRep("15900000008", "110101199001025008",
                "语音八厂", "91110000000000178X", legalRep);
        VoiceApi.Draft draft = voiceApi.draftJob(legalRep.get(), orgId, "客服", 1, 23_000, null);
        String strangerPhone = "15900000009";
        long stranger = identityApi.loginByPhone(strangerPhone, codes.issue(strangerPhone)).userId();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        voiceApi.confirm(draft.sessionId(), stranger, "对"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("自己的语音会话");
    }
}
