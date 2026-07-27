package com.xbb.agreement;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.agreement.internal.Agreement;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class AgreementServiceTest {

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
    @Autowired OpsApi opsApi;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    /** 走到"已录用"(协议此时应已自动生成),返回 [applicationId, legalRep, worker]。 */
    private long[] acceptedApplication(String legalRepPhone, String legalRepId, String workerPhone,
                                        String workerIdNumber, String orgName, String creditCode) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, orgName, creditCode, legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "普工", "描述", 30000, legalRep)));
        long jobId = jobIdHolder.get();

        long worker = verifiedUser(workerPhone, "工人" + workerPhone.substring(8), workerIdNumber);
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, worker)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);

        await().atMost(Duration.ofSeconds(15)).until(() ->
                agreementApi.findByApplicationId(applicationId).isPresent());
        return new long[]{applicationId, legalRep, worker};
    }

    @Test
    void 录用后自动生成待签协议且带存证哈希() {
        long[] ids = acceptedApplication("15800000001", "110101199001024001",
                "15800000002", "110101199001024002", "协议一厂", "91110000000000141X");

        AgreementApi.AgreementView view = agreementApi.findByApplicationId(ids[0]).orElseThrow();
        assertThat(view.status()).isEqualTo(Agreement.Status.PENDING);
        assertThat(view.contentHash()).hasSize(64);
        assertThat(view.workerUserId()).isEqualTo(ids[2]);
        assertThat(view.content()).contains("劳务协议");
    }

    @Test
    void 工人可以用短信因子签署自己的协议() {
        long[] ids = acceptedApplication("15800000003", "110101199001024003",
                "15800000004", "110101199001024004", "协议二厂", "91110000000000142X");

        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(ids[0], ids[2], "SMS"));

        AgreementApi.AgreementView view = agreementApi.findByApplicationId(ids[0]).orElseThrow();
        assertThat(view.status()).isEqualTo(Agreement.Status.SIGNED);
        assertThat(view.providerRef()).startsWith("MOCK-");
    }

    // ---------- 身份因子是法律效力要件(§6.2),不能省 ----------

    @Test
    void 不带身份因子不能签署() {
        long[] ids = acceptedApplication("15800000005", "110101199001024005",
                "15800000006", "110101199001024006", "协议三厂", "91110000000000143X");

        assertThatThrownBy(() -> agreementApi.sign(ids[0], ids[2], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身份因子");
    }

    @Test
    void 非法的身份因子不能签署() {
        long[] ids = acceptedApplication("15800000007", "110101199001024007",
                "15800000008", "110101199001024008", "协议四厂", "91110000000000144X");

        assertThatThrownBy(() -> agreementApi.sign(ids[0], ids[2], "随便糊弄一下"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("身份因子");
    }

    @Test
    void 别人不能替工人签署() {
        long[] ids = acceptedApplication("15800000009", "110101199001024009",
                "15800000010", "110101199001024010", "协议五厂", "91110000000000145X");

        // 法人代表也不行——协议乙方是工人本人
        assertThatThrownBy(() -> agreementApi.sign(ids[0], ids[1], "FACE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本人");
    }

    @Test
    void 已签署的协议不能重复签署() {
        long[] ids = acceptedApplication("15800000011", "110101199001024011",
                "15800000012", "110101199001024012", "协议六厂", "91110000000000146X");
        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(ids[0], ids[2], "FACE"));

        assertThatThrownBy(() -> agreementApi.sign(ids[0], ids[2], "FACE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复签署");
    }

    @Test
    void 签署不会改变存证哈希() {
        long[] ids = acceptedApplication("15800000013", "110101199001024013",
                "15800000014", "110101199001024014", "协议七厂", "91110000000000147X");
        String before = agreementApi.findByApplicationId(ids[0]).orElseThrow().contentHash();

        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(ids[0], ids[2], "SMS"));

        // 存证的意义就在于签署前后正文可证明未被篡改
        assertThat(agreementApi.findByApplicationId(ids[0]).orElseThrow().contentHash()).isEqualTo(before);
    }

    @Test
    void 不存在的协议签署报错() {
        assertThatThrownBy(() -> agreementApi.sign(999999L, 1L, "SMS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议不存在");
    }

    // ---------- 履约门禁(§6.2:没签不让到岗) ----------

    @Test
    void 协议未签署不能确认履约完成() {
        long[] ids = acceptedApplication("15800000015", "110101199001024015",
                "15800000016", "110101199001024016", "协议八厂", "91110000000000148X");

        assertThatThrownBy(() -> engagementApi.completeApplication(ids[0], ids[1]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("协议尚未签署");
    }

    @Test
    void 协议签署后可以确认履约完成() {
        long[] ids = acceptedApplication("15800000017", "110101199001024017",
                "15800000018", "110101199001024018", "协议九厂", "91110000000000149X");

        // 协议由录用事件异步触发生成,先等它到位
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                agreementApi.sign(ids[0], ids[2], "FACE"));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        engagementApi.completeApplication(ids[0], ids[1])));

        assertThat(engagementApi.findApplication(ids[0]).orElseThrow().status())
                .isEqualTo(com.xbb.engagement.internal.Application.Status.COMPLETED);
    }

    // ---------- 模板版本化(§6.2) ----------

    @Test
    void 协议记下了生成时所用的模板版本() {
        long[] ids = acceptedApplication("15800000019", "110101199001024019",
                "15800000020", "110101199001024020", "协议十厂", "91110000000000211X");

        AgreementApi.AgreementView view = agreementApi.findByApplicationId(ids[0]).orElseThrow();
        int activeVersion = opsApi.activeTemplate(OpsApi.LABOR_AGREEMENT).orElseThrow().version();

        assertThat(view.templateKey()).isEqualTo(OpsApi.LABOR_AGREEMENT);
        assertThat(view.templateVersion()).isEqualTo(activeVersion);
    }

    /**
     * 模板改版是**向前生效**的。已签协议的正文和版本号都必须钉死在当时那一版上——
     * 这是纠纷举证的前提:拿协议上的版本号能翻出当时的标准文本。
     */
    @Test
    void 模板发新版后老协议仍指向老版本且能按版本号回溯正文() {
        long[] ids = acceptedApplication("15800000021", "110101199001024021",
                "15800000022", "110101199001024022", "协议十一厂", "91110000000000212X");
        AgreementApi.AgreementView before = agreementApi.findByApplicationId(ids[0]).orElseThrow();
        int oldVersion = before.templateVersion();
        String oldBody = opsApi.activeTemplate(OpsApi.LABOR_AGREEMENT).orElseThrow().body();

        int newVersion = opsApi.publishTemplate(OpsApi.LABOR_AGREEMENT,
                oldBody + "\n五、本条为新版补充条款\n");
        try {
            assertThat(newVersion).isEqualTo(oldVersion + 1);

            AgreementApi.AgreementView after = agreementApi.findByApplicationId(ids[0]).orElseThrow();
            assertThat(after.templateVersion()).isEqualTo(oldVersion);
            assertThat(after.content()).isEqualTo(before.content());
            assertThat(after.contentHash()).isEqualTo(before.contentHash());

            // 举证路径:版本号 → 当时的模板正文
            assertThat(opsApi.templateVersion(OpsApi.LABOR_AGREEMENT, oldVersion).orElseThrow().body())
                    .isEqualTo(oldBody)
                    .doesNotContain("新版补充条款");

            // 新版发布后生成的协议用新版
            long[] later = acceptedApplication("15800000023", "110101199001024023",
                    "15800000024", "110101199001024024", "协议十二厂", "91110000000000213X");
            AgreementApi.AgreementView latest = agreementApi.findByApplicationId(later[0]).orElseThrow();
            assertThat(latest.templateVersion()).isEqualTo(newVersion);
            assertThat(latest.content()).contains("新版补充条款");
        } finally {
            // 还原成标准文本,别把后续用例的协议正文都带上这条测试条款
            opsApi.publishTemplate(OpsApi.LABOR_AGREEMENT, oldBody);
        }
    }

    @Test
    void 同一个模板同时只有一版生效() {
        String body = opsApi.activeTemplate(OpsApi.LABOR_AGREEMENT).orElseThrow().body();
        int previous = opsApi.activeTemplate(OpsApi.LABOR_AGREEMENT).orElseThrow().version();

        int published = opsApi.publishTemplate(OpsApi.LABOR_AGREEMENT, body);

        assertThat(opsApi.activeTemplate(OpsApi.LABOR_AGREEMENT).orElseThrow().version())
                .isEqualTo(published);
        assertThat(opsApi.templateVersion(OpsApi.LABOR_AGREEMENT, previous).orElseThrow().active())
                .isFalse();
    }
}
