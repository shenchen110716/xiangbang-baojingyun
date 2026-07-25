package com.xbb.engagement;

import com.xbb.TestcontainersConfig;
import com.xbb.engagement.internal.EngagementApprovedOrgRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
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
class OrgEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired EngagementApprovedOrgRepository approvedOrgs;

    @Test
    void 组织审核通过事件被履约域订阅并落地只读副本() {
        String phone = "15200000002";
        long legalRepUserId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(legalRepUserId, "孙法人", "110101199001015002");

        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(
                        Organization.Type.FACTORY, "十五号工厂", "91110000000000151X", legalRepUserId)));
        long orgId = orgIdHolder.get();

        orgApi.approve(orgId);

        await().atMost(Duration.ofSeconds(5)).until(() -> approvedOrgs.findById(orgId).isPresent());
        var approved = approvedOrgs.findById(orgId).orElseThrow();
        assertThat(approved.getLegalRepUserId()).isEqualTo(legalRepUserId);
    }
}
