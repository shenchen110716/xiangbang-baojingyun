package com.xbb.fund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.TestCodeAccessor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class FundControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired TestCodeAccessor codes;
    @Autowired ObjectMapper json;

    @Test
    void 未带token发放被拒绝() throws Exception {
        mvc.perform(put("/api/fund/payouts/999999/disburse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 没有平台运维角色的人调用直接403() throws Exception {
        // 这两个动作的"主人"是平台自己,不是某个用户,所以要求 PLATFORM_OPS。
        // 修复前它们零鉴权:任何登录用户遍历 id 就能对任意工资单动手。
        String ordinary = tokenFor("18800000007");

        mvc.perform(put("/api/fund/payouts/999999/disburse").header("Authorization", "Bearer " + ordinary))
                .andExpect(status().isForbidden());
    }

    @Test
    void 运维角色调用不存在的记录返回400() throws Exception {
        ops.userId();   // 确保运维角色已授予
        String opsToken = tokenFor(TestPlatformOps.Accessor.PHONE);

        mvc.perform(put("/api/fund/payouts/999999/disburse").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isBadRequest());
    }

    private String tokenFor(String phone) throws Exception {
        // 验证码不再经 HTTP 回显(那是漏洞),从测试钩子取
        String code = codes.issue(phone);

        String loginBody = mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(loginBody).get("token").asText();
    }
}
