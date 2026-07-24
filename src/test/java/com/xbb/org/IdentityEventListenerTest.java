package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.org.internal.VerifiedUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 跨模块集成点(identity 事件 -> org 副本),故意用整体 @SpringBootTest
 * 而不是 @ApplicationModuleTest —— 后者的模块切片对手动多数据源装配的
 * @Configuration bean(IdentityJpaConfig/OrgJpaConfig/JpaInfrastructureConfig)
 * 支持不稳定,实测 ALL_DEPENDENCIES 模式下 identity.internal 整个不被扫描,
 * Scenario/PublishedEvents 的参数解析器又是包私有、无法自己 @ExtendWith,
 * 索性直接用 Awaitility 轮询,等价效果。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityEventListenerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired VerifiedUserRepository verifiedUsers;

    @Test
    void 实名认证事件被组织域订阅并落地只读副本() {
        String phone = "13600000001";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        identityApi.verifyRealName(userId, "王五", "110101199001015678");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(verifiedUsers.findById(userId))
                        .isPresent()
                        .get()
                        .satisfies(v -> assertThat(v.getRealName()).isEqualTo("王五")));
    }
}
