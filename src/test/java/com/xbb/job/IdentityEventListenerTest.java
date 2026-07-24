package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.internal.JobVerifiedUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired JobVerifiedUserRepository verifiedUsers;

    @Test
    void 实名认证事件被工作域订阅并落地只读副本() {
        String phone = "13400000002";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        identityApi.verifyRealName(userId, "孙实名", "110101199001014002");

        await().atMost(Duration.ofSeconds(5)).until(() -> verifiedUsers.findById(userId).isPresent());
        assertThat(verifiedUsers.findById(userId)).isPresent();
    }
}
