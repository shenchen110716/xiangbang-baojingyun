package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.job.internal.ApprovedOrgRepository;
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

/**
 * job 域不能直接读 org.internal 的 VerifiedUser 副本(那会违反铁律2,
 * 跨模块引用了别人的 internal)——等 org.submit() 自己内部靠 org 域的
 * 实名副本校验通过为止,是从 job 测试视角唯一站得住脚的等待方式。
 */
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
    @Autowired ApprovedOrgRepository approvedOrgs;

    @Test
    void 组织审核通过事件被工作域订阅并落地只读副本() {
        String phone = "13400000001";
        long legalRepUserId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(legalRepUserId, "赵法人", "110101199001014001");

        AtomicLong orgIdHolder = new AtomicLong();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                orgIdHolder.set(orgApi.submit(
                        Organization.Type.FACTORY, "四号工厂", "91110000000000041X", legalRepUserId)));
        long orgId = orgIdHolder.get();

        orgApi.approve(orgId);

        await().atMost(Duration.ofSeconds(5)).until(() -> approvedOrgs.findById(orgId).isPresent());
        var approved = approvedOrgs.findById(orgId).orElseThrow();
        assertThat(approved.getLegalRepUserId()).isEqualTo(legalRepUserId);
    }
}
