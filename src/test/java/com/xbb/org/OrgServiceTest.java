package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
import com.xbb.org.internal.VerifiedUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class OrgServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired OrgApi orgApi;
    @Autowired VerifiedUserRepository verifiedUsers;

    private long verifiedUser(String phone, String realName, String idNumber) {
        long userId = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(userId, realName, idNumber);
        await().atMost(Duration.ofSeconds(5)).until(() -> verifiedUsers.findById(userId).isPresent());
        return userId;
    }

    @Test
    void 已验证用户可以提交组织入驻() {
        long userId = verifiedUser("13500000001", "赵六", "110101199001019001");

        long orgId = orgApi.submit(Organization.Type.FACTORY, "六号工厂", "91110000000000001X", userId);

        var view = orgApi.findById(orgId).orElseThrow();
        assertThat(view.status()).isEqualTo(Organization.Status.PENDING);
    }

    @Test
    void 未验证用户不能提交组织入驻() {
        assertThatThrownBy(() -> orgApi.submit(Organization.Type.ENTERPRISE, "黑户企业", "91110000000000002X", 999_999L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未实名");
    }

    @Test
    void 审核通过后状态变更() {
        long userId = verifiedUser("13500000002", "孙七", "110101199001019002");
        long orgId = orgApi.submit(Organization.Type.SERVICE_STATION, "七号服务站", "91110000000000003X", userId);

        orgApi.approve(orgId);

        assertThat(orgApi.findById(orgId).orElseThrow().status()).isEqualTo(Organization.Status.APPROVED);
    }

    @Test
    void 已审核的组织不能重复审核() {
        long userId = verifiedUser("13500000003", "周八", "110101199001019003");
        long orgId = orgApi.submit(Organization.Type.ENTERPRISE, "八号企业", "91110000000000004X", userId);
        orgApi.approve(orgId);

        assertThatThrownBy(() -> orgApi.reject(orgId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待审核");
    }
}
