package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信授权登录与绑定。
 *
 * <p>守的要害是**不凭空建账号**:平台的核心动作(报名、收工资、当业务员)都要求实名,
 * 而实名要手机号。让微信独立开户会得到一批走不下去的空账号,
 * 而且同一个人后来用手机登录时又是另一个账号 —— 两边的业绩和佣金再也对不起来。
 *
 * <p>号段 188。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class WechatLoginTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    private long phoneLogin(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    @Test
    void 微信没绑过时不建账号() {
        var r = identityApi.loginByWechat("brand-new-code");
        // **不建账号**。建了的话这个人后来用手机登录会是另一个账号,
        // 业绩和佣金分在两边,再也对不起来
        assertThat(r.userId()).isZero();
        assertThat(r.newUser()).isTrue();
        assertThat(r.token()).isEmpty();
    }

    @Test
    void 绑定之后微信能直接登录到同一个账号() {
        long userId = phoneLogin("18800000001");
        identityApi.bindWechat(userId, "code-A");

        var r = identityApi.loginByWechat("code-A");
        assertThat(r.userId()).as("要登录到同一个账号,不是新建一个").isEqualTo(userId);
        assertThat(r.newUser()).isFalse();
        assertThat(r.token()).isNotBlank();
        assertThat(identityApi.wechatOpenIdOf(userId)).isPresent();
    }

    @Test
    void 一个微信不能绑两个账号() {
        long first = phoneLogin("18800000002");
        long second = phoneLogin("18800000003");
        identityApi.bindWechat(first, "code-B");

        // 允许的话,一份操作员授权能被两个账号使用
        assertThatThrownBy(() -> identityApi.bindWechat(second, "code-B"))
                .hasMessageContaining("已经绑定了别的账号");
    }

    @Test
    void 一个账号不能绑两个微信() {
        long userId = phoneLogin("18800000004");
        identityApi.bindWechat(userId, "code-C");
        assertThatThrownBy(() -> identityApi.bindWechat(userId, "code-D"))
                .hasMessageContaining("已经绑过微信");
    }

    @Test
    void 重复绑同一个微信是幂等的() {
        long userId = phoneLogin("18800000005");
        identityApi.bindWechat(userId, "code-E");
        // 网络重试会让同一个请求到两次。第二次该静默通过,而不是报"已经绑过"
        identityApi.bindWechat(userId, "code-E");
        assertThat(identityApi.wechatOpenIdOf(userId)).isPresent();
    }

    @Test
    void 空授权码被拒绝() {
        long userId = phoneLogin("18800000006");
        // mock 通道不该比真实通道宽松 —— 那样测试过得去的东西上线会当场失败
        assertThatThrownBy(() -> identityApi.bindWechat(userId, "  "))
                .hasMessageContaining("授权码");
    }

    @Test
    void 没绑微信时查不到openid() {
        long userId = phoneLogin("18800000007");
        assertThat(identityApi.wechatOpenIdOf(userId)).isEmpty();
    }
}
