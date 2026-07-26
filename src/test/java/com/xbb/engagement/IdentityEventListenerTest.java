package com.xbb.engagement;

import com.xbb.TestcontainersConfig;
import com.xbb.engagement.internal.EngagementVerifiedUserRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
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
    @Autowired EngagementVerifiedUserRepository verifiedUsers;

    @Test
    void 实名认证事件被履约域订阅并落地只读副本() {
        String phone = "15200000001";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        identityApi.verifyRealName(userId, "钱实名", "110101199001015001");

        await().atMost(Duration.ofSeconds(15)).until(() -> verifiedUsers.findById(userId).isPresent());
        assertThat(verifiedUsers.findById(userId)).isPresent();
    }
}
