package com.xbb.engagement;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.engagement.internal.Application;
import com.xbb.engagement.internal.EngagementApprovedOrgRepository;
import com.xbb.engagement.internal.EngagementVerifiedUserRepository;
import com.xbb.engagement.internal.PostedJobRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
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
class EngagementServiceTest {

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
    @Autowired EngagementApprovedOrgRepository approvedOrgs;
    @Autowired EngagementVerifiedUserRepository verifiedUsers;
    @Autowired PostedJobRepository postedJobs;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(15)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    private long approvedOrg(long legalRepUserId, String name, String creditCode) {
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(com.xbb.org.api.OrgType.FACTORY, name, creditCode, legalRepUserId)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId, ops.userId());
        await().atMost(Duration.ofSeconds(15)).until(() -> approvedOrgs.findById(orgId).isPresent());
        return orgId;
    }

    private long postedJob(long orgId, String title, long wageCents, long callerUserId) {
        // 岗位域的组织副本同样是异步落地的,发岗前得等它到位,否则报"组织未通过审核"
        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, title, "描述", wageCents, callerUserId)));
        long jobId = jobIdHolder.get();
        // engagement 的岗位副本靠订阅 JobPosted 事件异步落地,先等它出现,
        // 否则 apply()/acceptApplication() 会因为查不到本域的 posted_job 而误报"岗位不存在"。
        await().atMost(Duration.ofSeconds(15)).until(() -> postedJobs.findById(jobId).isPresent());
        return jobId;
    }

    @Test
    void 已实名用户可以报名() {
        long legalRep = verifiedUser("13300000005", "法人四", "110101199001013005");
        long orgId = approvedOrg(legalRep, "四号工厂", "91110000000000054X");
        long jobId = postedJob(orgId, "质检员", 2800, legalRep);
        long applicant = verifiedUser("13300000006", "应聘者一", "110101199001013006");

        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));

        assertThat(applicationIdHolder.get()).isPositive();
    }

    @Test
    void 未实名用户报名被拒() {
        long legalRep = verifiedUser("13300000007", "法人五", "110101199001013007");
        long orgId = approvedOrg(legalRep, "五号工厂", "91110000000000055X");
        long jobId = postedJob(orgId, "搬运工", 2700, legalRep);
        long unverifiedApplicant = identityApi.loginByPhone("13300000008", codes.issue("13300000008")).userId();

        assertThatThrownBy(() -> engagementApi.apply(jobId, unverifiedApplicant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实名认证");
    }

    @Test
    void 法人代表可以录用应聘者() {
        long legalRep = verifiedUser("13300000010", "法人六", "110101199001013010");
        long orgId = approvedOrg(legalRep, "六号工厂", "91110000000000056X");
        long jobId = postedJob(orgId, "打包工", 2500, legalRep);
        long applicant = verifiedUser("13300000011", "应聘者二", "110101199001013011");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();

        engagementApi.acceptApplication(applicationId, legalRep);

        assertThat(engagementApi.findApplication(applicationId, ops.userId()).orElseThrow().status())
                .isEqualTo(com.xbb.engagement.api.ApplicationStatus.ACCEPTED);
    }

    @Test
    void 非法人代表不能录用应聘者() {
        long legalRep = verifiedUser("13300000012", "法人七", "110101199001013012");
        long orgId = approvedOrg(legalRep, "七号工厂", "91110000000000057X");
        long jobId = postedJob(orgId, "分拣工", 2400, legalRep);
        long applicant = verifiedUser("13300000013", "应聘者三", "110101199001013013");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();
        long stranger = verifiedUser("13300000014", "路人二", "110101199001013014");

        assertThatThrownBy(() -> engagementApi.acceptApplication(applicationId, stranger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("法人代表");
    }

    @Test
    void 已处理的应聘不能重复处理() {
        long legalRep = verifiedUser("13300000015", "法人八", "110101199001013015");
        long orgId = approvedOrg(legalRep, "八号工厂", "91110000000000058X");
        long jobId = postedJob(orgId, "客服", 2300, legalRep);
        long applicant = verifiedUser("13300000016", "应聘者四", "110101199001013016");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);

        assertThatThrownBy(() -> engagementApi.rejectApplication(applicationId, legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待处理");
    }

    // ---------- 履约完成(Plan9:§5.3 R1"只有完成的履约单可评"的锚点) ----------

    /** 建一条已录用的报名,把法人代表 id 通过 legalRepOut 带回给调用方。 */
    private long acceptedApplication(String legalRepPhone, String legalRepId, String applicantPhone,
                                      String applicantId, String orgName, String creditCode,
                                      AtomicLong legalRepOut) {
        long legalRep = verifiedUser(legalRepPhone, "法人" + legalRepPhone.substring(8), legalRepId);
        legalRepOut.set(legalRep);
        long orgId = approvedOrg(legalRep, orgName, creditCode);
        long jobId = postedJob(orgId, "普工", 2500, legalRep);
        long applicant = verifiedUser(applicantPhone, "应聘者" + applicantPhone.substring(8), applicantId);
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));
        long applicationId = applicationIdHolder.get();
        engagementApi.acceptApplication(applicationId, legalRep);
        // 协议签署是履约完成的前置门禁(§6.2)
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                // 协议由录用事件异步触发生成,先等它到位
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        agreementApi.sign(applicationId, applicant, "SMS")));
        return applicationId;
    }

    @Test
    void 已录用的报名可以完成() {
        AtomicLong legalRep = new AtomicLong();
        long applicationId = acceptedApplication(
                "15400000001", "110101199001017001", "15400000002", "110101199001017002",
                "完成测试一厂", "91110000000000101X", legalRep);

        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep.get()));

        assertThat(engagementApi.findApplication(applicationId, ops.userId()).orElseThrow().status())
                .isEqualTo(com.xbb.engagement.api.ApplicationStatus.COMPLETED);
    }

    @Test
    void 未录用的报名不能直接完成() {
        long legalRep = verifiedUser("15400000003", "法人完二", "110101199001017003");
        long orgId = approvedOrg(legalRep, "完成测试二厂", "91110000000000102X");
        long jobId = postedJob(orgId, "普工", 2500, legalRep);
        long applicant = verifiedUser("15400000004", "应聘者完二", "110101199001017004");
        AtomicLong applicationIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                applicationIdHolder.set(engagementApi.apply(jobId, applicant)));

        assertThatThrownBy(() -> engagementApi.completeApplication(applicationIdHolder.get(), legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已录用");
    }

    @Test
    void 已完成的报名不能重复完成() {
        AtomicLong legalRep = new AtomicLong();
        long applicationId = acceptedApplication(
                "15400000005", "110101199001017005", "15400000006", "110101199001017006",
                "完成测试三厂", "91110000000000103X", legalRep);
        // 履约域的"已签协议"副本靠订阅 AgreementSigned 异步落地,签完不等于本域已知晓
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.completeApplication(applicationId, legalRep.get()));

        assertThatThrownBy(() -> engagementApi.completeApplication(applicationId, legalRep.get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已录用");
    }

    @Test
    void 非法人代表不能确认履约完成() {
        AtomicLong legalRep = new AtomicLong();
        long applicationId = acceptedApplication(
                "15400000007", "110101199001017007", "15400000008", "110101199001017008",
                "完成测试四厂", "91110000000000104X", legalRep);
        long stranger = verifiedUser("15400000009", "路人完四", "110101199001017009");

        assertThatThrownBy(() -> engagementApi.completeApplication(applicationId, stranger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("法人代表");
    }
}
