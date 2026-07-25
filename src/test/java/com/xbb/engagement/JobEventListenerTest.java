package com.xbb.engagement;

import com.xbb.TestcontainersConfig;
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
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class JobEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired PostedJobRepository postedJobs;

    @Test
    void 岗位发布事件被履约域订阅并落地只读副本() {
        String phone = "15100000003";
        long legalRep = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(legalRep, "李法人", "110101199001015003");

        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(Organization.Type.FACTORY, "十二号工厂", "91110000000000082X", legalRep)));
        long orgId = orgIdHolder.get();
        orgApi.approve(orgId);

        AtomicLong jobIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                jobIdHolder.set(jobApi.postJob(orgId, "包装工", "产线包装", 2900, legalRep)));
        long jobId = jobIdHolder.get();

        await().atMost(Duration.ofSeconds(5)).until(() -> postedJobs.findById(jobId).isPresent());
        var posted = postedJobs.findById(jobId).orElseThrow();
        assertThat(posted.getOrgId()).isEqualTo(orgId);
        assertThat(posted.getWageCents()).isEqualTo(2900);
    }
}
