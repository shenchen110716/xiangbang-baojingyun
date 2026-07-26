package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.job.internal.ApprovedOrgRepository;
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

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        return userId;
    }

    private long approvedOrg(long legalRepUserId, String name, String creditCode) {
        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, name, creditCode, legalRepUserId)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);
        await().atMost(Duration.ofSeconds(15)).until(() -> approvedOrgs.findById(orgId).isPresent());
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
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.SERVICE_STATION, "三号服务站", "91110000000000053X", legalRep)));
        long orgId = orgIdHolder.get();
        // 故意不 approve

        assertThatThrownBy(() -> jobApi.postJob(orgId, "配送员", "同城配送", 2600, legalRep))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未通过审核");
    }
}
