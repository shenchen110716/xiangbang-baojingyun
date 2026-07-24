package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class JobControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    @Test
    void 未带token发布岗位被拒绝() throws Exception {
        mvc.perform(post("/api/job")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":1,\"title\":\"测试岗位\",\"description\":\"测试\",\"wageCents\":2000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 为不存在的组织发布岗位返回409() throws Exception {
        String phone = "13300000009";
        String token = identityApi.loginByPhone(phone, codes.issue(phone)).token();

        mvc.perform(post("/api/job")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":999999,\"title\":\"测试岗位\",\"description\":\"测试\",\"wageCents\":2000}"))
                .andExpect(status().isConflict());
    }
}
