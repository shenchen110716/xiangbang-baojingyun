package com.xbb.identity;

import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.identity.internal.UserRole;
import com.xbb.identity.internal.UserRoleRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用的平台运维身份。
 *
 * <p>组织审核、放款、结算作废、佣金支付这几个动作的"主人"是平台自己,不是某个用户,
 * 所以它们要求 {@link Role#PLATFORM_OPS}。绝大多数测试只是把它们当**前置步骤**用,
 * 不是在测授权本身(授权链由 RoleTest 与 OutboxOpsControllerTest 覆盖),
 * 因此这里直接写角色行,不绕引导管理员那一圈。
 */
@TestConfiguration
public class TestPlatformOps {

    @Bean
    Accessor platformOpsAccessor(IdentityApi identityApi, TestCodeAccessor codes, UserRoleRepository roles) {
        return new Accessor(identityApi, codes, roles);
    }

    public static class Accessor {

        /** 固定账号,所有测试共用;号段 199 不与任何业务用例重叠。 */
        public static final String PHONE = "19900000001";

        private final IdentityApi identityApi;
        private final TestCodeAccessor codes;
        private final UserRoleRepository roles;
        private volatile Long cached;

        Accessor(IdentityApi identityApi, TestCodeAccessor codes, UserRoleRepository roles) {
            this.identityApi = identityApi;
            this.codes = codes;
            this.roles = roles;
        }

        @Transactional("identityTransactionManager")
        public String phone() { return PHONE; }

        public long userId() {
            Long known = cached;
            if (known != null) {
                return known;
            }
            long id = identityApi.loginByPhone(PHONE, codes.issue(PHONE)).userId();
            if (!roles.existsByUserIdAndRole(id, Role.PLATFORM_OPS)) {
                roles.save(new UserRole(id, Role.PLATFORM_OPS));
            }
            cached = id;
            return id;
        }
    }
}
