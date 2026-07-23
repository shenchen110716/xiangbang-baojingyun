package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ApplicationModuleTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    @Test
    void 首次手机登录自动建号并生成邀请码() {
        String phone = "13800000001";
        String code = codes.issue(phone);

        var result = identityApi.loginByPhone(phone, code);

        assertThat(result.newUser()).isTrue();
        assertThat(result.token()).isNotBlank();

        var user = identityApi.findVerifiedUser(result.userId()).orElseThrow();
        assertThat(user.phone()).isEqualTo(phone);
        assertThat(user.inviteCode()).isNotBlank();
        assertThat(user.verified()).isFalse();
    }

    @Test
    void 二次登录不重复建号() {
        String phone = "13800000002";
        var first = identityApi.loginByPhone(phone, codes.issue(phone));
        var second = identityApi.loginByPhone(phone, codes.issue(phone));

        assertThat(second.newUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
    }

    @Test
    void 验证码错误必须拒绝() {
        String phone = "13800000003";
        codes.issue(phone);

        assertThatThrownBy(() -> identityApi.loginByPhone(phone, "000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码");
    }
}
