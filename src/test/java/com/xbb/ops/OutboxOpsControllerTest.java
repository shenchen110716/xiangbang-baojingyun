package com.xbb.ops;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维出口的权限边界。
 *
 * <p>这个接口能重放资金事件,是全站权限最高的一个,所以边界必须在 HTTP 层
 * 也被真正验证——服务层判断对了、接口没接上,等于没判断。
 */
@SpringBootTest(properties = "xbb.security.bootstrap-admin-phones=16500000001")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class OutboxOpsControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    private IdentityApi.LoginResult login(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone));
    }

    @Test
    void 未带token直接401() throws Exception {
        mvc.perform(get("/api/ops/outbox/stuck")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ops/outbox/fund/some-event/replay"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 登录了但没有运维角色是403而不是401() throws Exception {
        String token = login("16500000002").token();

        // 401 是"你没登录",403 是"你没这个权限"——调用方要能区分
        mvc.perform(get("/api/ops/outbox/stuck").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/outbox/fund/some-event/replay")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 平台管理员没有运维角色也进不去() throws Exception {
        // 管理员管的是角色,不是运维操作。要重放事件就得先给自己授运维角色,
        // 这一步会留下审计痕迹,而"管理员天然什么都能干"不会。
        String adminToken = login("16500000001").token();

        mvc.perform(get("/api/ops/outbox/stuck").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 有运维角色才能看卡死清单() throws Exception {
        long admin = login("16500000001").userId();
        IdentityApi.LoginResult ops = login("16500000003");
        identityApi.grantRole(ops.userId(), Role.PLATFORM_OPS, admin);

        mvc.perform(get("/api/ops/outbox/stuck").header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isOk());
    }

    @Test
    void 收回角色后同一个token立刻失效() throws Exception {
        long admin = login("16500000001").userId();
        IdentityApi.LoginResult ops = login("16500000004");
        identityApi.grantRole(ops.userId(), Role.PLATFORM_OPS, admin);
        String token = ops.token();
        mvc.perform(get("/api/ops/outbox/stuck").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        identityApi.revokeRole(ops.userId(), Role.PLATFORM_OPS, admin);

        // 这正是角色不写进 JWT 的理由:**同一个 token**,权限收回后立刻就用不了了。
        // 若把角色签进 token,这里会一直放行到 token 过期为止。
        mvc.perform(get("/api/ops/outbox/stuck").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void 重放不存在的事件返回404() throws Exception {
        long admin = login("16500000001").userId();
        IdentityApi.LoginResult ops = login("16500000005");
        identityApi.grantRole(ops.userId(), Role.PLATFORM_OPS, admin);

        mvc.perform(post("/api/ops/outbox/fund/根本不存在的事件/replay")
                        .header("Authorization", "Bearer " + ops.token()))
                .andExpect(status().isNotFound());
    }
}
