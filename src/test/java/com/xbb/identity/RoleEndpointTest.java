package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.api.IdentityApi;
import com.xbb.identity.api.Role;
import com.xbb.identity.internal.UserRole;
import com.xbb.identity.internal.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理端点的守卫。
 *
 * <p>这几个端点是**授权链的入口**:写松了,任何登录用户都能给自己授 PLATFORM_OPS,
 * 然后审核组织、放款、作废结算——整套 RBAC 直接作废。所以这里成对验:
 * 该拒的拒、该放的放。只验"管理员能授"不能证明"别人不能授"。
 *
 * <p>号段 167,与其它测试不重叠。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class RoleEndpointTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired UserRoleRepository roles;

    private record U(long id, String token) { }

    private U login(String phone) {
        var r = identityApi.loginByPhone(phone, codes.issue(phone));
        return new U(r.userId(), r.token());
    }

    private String body(long target, Role role) {
        return "{\"targetUserId\":" + target + ",\"role\":\"" + role + "\"}";
    }

    @Test
    void 普通用户不能给自己授平台运维() throws Exception {
        U u = login("16700000001");
        mvc.perform(post("/api/identity/roles")
                        .header("Authorization", "Bearer " + u.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(u.id(), Role.PLATFORM_OPS)))
                .andExpect(status().is4xxClientError());

        // 断言到数据库,不只看状态码:控制器返回 4xx 但服务层已经写进去的话,
        // 只看响应是发现不了的。
        org.assertj.core.api.Assertions
                .assertThat(roles.existsByUserIdAndRole(u.id(), Role.PLATFORM_OPS)).isFalse();
    }

    @Test
    void 管理员能授予运维角色并且对方随即可用() throws Exception {
        U admin = login("16700000002");
        roles.save(new UserRole(admin.id(), Role.PLATFORM_ADMIN));
        U target = login("16700000003");

        mvc.perform(post("/api/identity/roles")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.id(), Role.PLATFORM_OPS)))
                .andExpect(status().isNoContent());

        // 授完就能用:待审核队列此前对他是 4xx
        mvc.perform(get("/api/org/pending").header("Authorization", "Bearer " + target.token()))
                .andExpect(status().isOk());
    }

    @Test
    void 收回之后立刻失效() throws Exception {
        U admin = login("16700000004");
        roles.save(new UserRole(admin.id(), Role.PLATFORM_ADMIN));
        U target = login("16700000005");
        roles.save(new UserRole(target.id(), Role.PLATFORM_OPS));

        mvc.perform(delete("/api/identity/roles")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(target.id(), Role.PLATFORM_OPS)))
                .andExpect(status().isNoContent());

        // 角色不写进 JWT 就是为了这个:收回立刻生效,不用等 token 过期(铁律 5)。
        // target 手上那个 token 一个字没变。
        mvc.perform(get("/api/org/pending").header("Authorization", "Bearer " + target.token()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 查自己的角色() throws Exception {
        U u = login("16700000006");
        roles.save(new UserRole(u.id(), Role.PLATFORM_OPS));
        mvc.perform(get("/api/identity/roles").header("Authorization", "Bearer " + u.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'PLATFORM_OPS')]").exists());
    }

    @Test
    void 未登录访问角色端点被拒() throws Exception {
        mvc.perform(get("/api/identity/roles")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/identity/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, Role.PLATFORM_OPS)))
                .andExpect(status().isUnauthorized());
    }
}
