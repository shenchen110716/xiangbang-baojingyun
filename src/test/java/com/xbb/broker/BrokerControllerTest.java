package com.xbb.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.TestcontainersConfig;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class BrokerControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired TestCodeAccessor codes;
    @Autowired ObjectMapper json;

    @Test
    void 未带token注册经纪人被拒绝() throws Exception {
        mvc.perform(post("/api/broker/register"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 未实名用户注册经纪人返回409() throws Exception {
        String phone = "13000000030";
        // 验证码不再经 HTTP 回显(那是漏洞),从测试钩子取
        String code = codes.issue(phone);
        String loginBody = mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(loginBody).get("token").asText();

        mvc.perform(post("/api/broker/register").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }
}
