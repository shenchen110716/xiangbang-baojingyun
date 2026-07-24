package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.UserVerified;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 普通 @SpringBootTest(原因见 IdentityServiceTest 的注释),事件断言改用
 * Spring Test 自带的 @RecordApplicationEvents/ApplicationEvents,
 * 不依赖 Modulith Scenario 的包私有参数解析器。
 */
@SpringBootTest
@RecordApplicationEvents
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityVerifyTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired ApplicationEvents events;

    @Test
    void 实名成功后置verified并发出事件() {
        String phone = "13900000001";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        identityApi.verifyRealName(userId, "张三", "110101199001011234");

        assertThat(events.stream(UserVerified.class))
                .anySatisfy(evt -> {
                    assertThat(evt.userId()).isEqualTo(userId);
                    assertThat(evt.realName()).isEqualTo("张三");
                });
        assertThat(identityApi.findVerifiedUser(userId).orElseThrow().verified()).isTrue();
    }

    @Test
    void 同一身份证不可绑定第二个账号() {
        String idNumber = "110101199001019999";

        String phoneA = "13900000002";
        long a = identityApi.loginByPhone(phoneA, codes.issue(phoneA)).userId();
        identityApi.verifyRealName(a, "李四", idNumber);

        String phoneB = "13900000003";
        long b = identityApi.loginByPhone(phoneB, codes.issue(phoneB)).userId();

        assertThatThrownBy(() -> identityApi.verifyRealName(b, "李四", idNumber))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已被绑定");
    }

    @Test
    void 已实名用户不可重复认证() {
        String phone = "13900000004";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, "王五", "110101199001019998");

        assertThatThrownBy(() -> identityApi.verifyRealName(userId, "别的名字", "110101199001019997"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已完成实名认证");
    }
}
