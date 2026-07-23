package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.UserVerified;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ApplicationModuleTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityVerifyTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    @Test
    void 实名成功后置verified并发出事件(Scenario scenario) {
        String phone = "13900000001";
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();

        scenario.stimulate(() -> identityApi.verifyRealName(userId, "张三", "110101199001011234"))
                .andWaitForEventOfType(UserVerified.class)
                .toArriveAndVerify(evt -> {
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
}
