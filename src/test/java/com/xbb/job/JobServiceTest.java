package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.job.internal.Application;
import com.xbb.job.internal.ApprovedOrgRepository;
import com.xbb.job.internal.JobVerifiedUserRepository;
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
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class JobServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired ApprovedOrgRepository approvedOrgs;
    @Autowired JobVerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(5)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    private long approvedOrg(long legalRepUserId, String name, String creditCode) {
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, name, creditCode, legalRepUserId)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);
        await().atMost(Duration.ofSeconds(5)).until(() -> approvedOrgs.findById(orgId).isPresent());
        return orgId;
    }

    @Test
    void 组织法人代表可以为已审核组织发布岗位() {
        long legalRep = verifiedUser("13300000001", "法人一", "110101199001013001");
        long orgId = approvedOrg(legalRep, "一号工厂", "91110000000000051X");

        long jobId = jobApi.postJob(orgId, "打包工", "仓库打包", 2500, legalRep);

        var view = jobApi.findJob(jobId).orElseThrow();
        assertThat(view.orgId()).isEqualTo(orgId);
        assertThat(view.title()).isEqualTo("打包工");
    }

    @Test
    void 非法人代表发布岗位被拒() {
        long legalRep = verifiedUser("13300000002", "法人二", "110101199001013002");
        long orgId = approvedOrg(legalRep, "二号工厂", "91110000000000052X");
        long stranger = verifiedUser("13300000003", "路人", "110101199001013003");

        assertThatThrownBy(() -> jobApi.postJob(orgId, "分拣工", "仓库分拣", 2400, stranger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("法人代表");
    }

    @Test
    void 未审核组织发布岗位被拒() {
        long legalRep = verifiedUser("13300000004", "法人三", "110101199001013004");
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.SERVICE_STATION, "三号服务站", "91110000000000053X", legalRep)));
        long orgId = orgIdHolder.get();
        // 故意不 approve

        assertThatThrownBy(() -> jobApi.postJob(orgId, "配送员", "同城配送", 2600, legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未通过审核");
    }

    @Test
    void 已实名用户可以报名() {
        long legalRep = verifiedUser("13300000005", "法人四", "110101199001013005");
        long orgId = approvedOrg(legalRep, "四号工厂", "91110000000000054X");
        long jobId = jobApi.postJob(orgId, "质检员", "产线质检", 2800, legalRep);
        long applicant = verifiedUser("13300000006", "应聘者一", "110101199001013006");

        long applicationId = jobApi.apply(jobId, applicant);

        assertThat(applicationId).isPositive();
    }

    @Test
    void 未实名用户报名被拒() {
        long legalRep = verifiedUser("13300000007", "法人五", "110101199001013007");
        long orgId = approvedOrg(legalRep, "五号工厂", "91110000000000055X");
        long jobId = jobApi.postJob(orgId, "搬运工", "仓库搬运", 2700, legalRep);
        long unverifiedApplicant = identityApi.loginByPhone("13300000008", codes.issue("13300000008")).userId();

        assertThatThrownBy(() -> jobApi.apply(jobId, unverifiedApplicant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实名认证");
    }

    @Test
    void 法人代表可以录用应聘者() {
        long legalRep = verifiedUser("13300000010", "法人六", "110101199001013010");
        long orgId = approvedOrg(legalRep, "六号工厂", "91110000000000056X");
        long jobId = jobApi.postJob(orgId, "打包工", "仓库打包", 2500, legalRep);
        long applicant = verifiedUser("13300000011", "应聘者二", "110101199001013011");
        long applicationId = jobApi.apply(jobId, applicant);

        jobApi.acceptApplication(applicationId, legalRep);

        assertThat(jobApi.findApplication(applicationId).orElseThrow().status())
                .isEqualTo(Application.Status.ACCEPTED);
    }

    @Test
    void 非法人代表不能录用应聘者() {
        long legalRep = verifiedUser("13300000012", "法人七", "110101199001013012");
        long orgId = approvedOrg(legalRep, "七号工厂", "91110000000000057X");
        long jobId = jobApi.postJob(orgId, "分拣工", "仓库分拣", 2400, legalRep);
        long applicant = verifiedUser("13300000013", "应聘者三", "110101199001013013");
        long applicationId = jobApi.apply(jobId, applicant);
        long stranger = verifiedUser("13300000014", "路人二", "110101199001013014");

        assertThatThrownBy(() -> jobApi.acceptApplication(applicationId, stranger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("法人代表");
    }

    @Test
    void 已处理的应聘不能重复处理() {
        long legalRep = verifiedUser("13300000015", "法人八", "110101199001013015");
        long orgId = approvedOrg(legalRep, "八号工厂", "91110000000000058X");
        long jobId = jobApi.postJob(orgId, "客服", "在线客服", 2300, legalRep);
        long applicant = verifiedUser("13300000016", "应聘者四", "110101199001013016");
        long applicationId = jobApi.apply(jobId, applicant);
        jobApi.acceptApplication(applicationId, legalRep);

        assertThatThrownBy(() -> jobApi.rejectApplication(applicationId, legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待处理");
    }
}
