package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台级角色(§4.2 身份域负责授权)。
 *
 * <p>引导名单里配了一个手机号,它是授权链的根——第一个管理员不可能由别的管理员授予。
 */
@SpringBootTest(properties = "xbb.security.bootstrap-admin-phones=16400000001")
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class RoleTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    private long login(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    @Test
    void 引导名单里的手机号登录后成为平台管理员() {
        long admin = login("16400000001");

        assertThat(identityApi.hasRole(admin, Role.PLATFORM_ADMIN)).isTrue();
    }

    @Test
    void 不在引导名单里的人登录后什么角色都没有() {
        long ordinary = login("16400000002");

        assertThat(identityApi.rolesOf(ordinary)).isEmpty();
        assertThat(identityApi.hasRole(ordinary, Role.PLATFORM_OPS)).isFalse();
    }

    @Test
    void 管理员可以授予运维角色() {
        long admin = login("16400000001");
        long ops = login("16400000003");

        identityApi.grantRole(ops, Role.PLATFORM_OPS, admin);

        assertThat(identityApi.hasRole(ops, Role.PLATFORM_OPS)).isTrue();
        // 授予运维不等于授予管理员——拿到运维权限的人不能再给别人升权
        assertThat(identityApi.hasRole(ops, Role.PLATFORM_ADMIN)).isFalse();
    }

    @Test
    void 运维不能给自己或别人升权() {
        long admin = login("16400000001");
        long ops = login("16400000004");
        identityApi.grantRole(ops, Role.PLATFORM_OPS, admin);

        // 授权链要有根:否则任何拿到运维权限的人都能把自己变成管理员
        assertThatThrownBy(() -> identityApi.grantRole(ops, Role.PLATFORM_ADMIN, ops))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有平台管理员");
        assertThat(identityApi.hasRole(ops, Role.PLATFORM_ADMIN)).isFalse();
    }

    @Test
    void 普通用户不能授予任何角色() {
        long ordinary = login("16400000005");
        long victim = login("16400000006");

        assertThatThrownBy(() -> identityApi.grantRole(victim, Role.PLATFORM_OPS, ordinary))
                .isInstanceOf(IllegalStateException.class);
        assertThat(identityApi.rolesOf(victim)).isEmpty();
    }

    @Test
    void 收回角色后立刻失效() {
        long admin = login("16400000001");
        long ops = login("16400000007");
        identityApi.grantRole(ops, Role.PLATFORM_OPS, admin);

        identityApi.revokeRole(ops, Role.PLATFORM_OPS, admin);

        // 立刻失效是这套设计的要点:角色不写进 JWT,就是为了不用等 token 过期
        assertThat(identityApi.hasRole(ops, Role.PLATFORM_OPS)).isFalse();
    }

    @Test
    void 不能收回自己最后的管理员权限() {
        long admin = login("16400000001");

        // 一次误操作就没人能再改角色了,这种自锁死必须挡住
        assertThatThrownBy(() -> identityApi.revokeRole(admin, Role.PLATFORM_ADMIN, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能收回自己");
        assertThat(identityApi.hasRole(admin, Role.PLATFORM_ADMIN)).isTrue();
    }

    @Test
    void 重复授予同一个角色不报错() {
        long admin = login("16400000001");
        long ops = login("16400000008");

        identityApi.grantRole(ops, Role.PLATFORM_OPS, admin);
        identityApi.grantRole(ops, Role.PLATFORM_OPS, admin);

        assertThat(identityApi.rolesOf(ops)).containsExactly(Role.PLATFORM_OPS);
    }
}
