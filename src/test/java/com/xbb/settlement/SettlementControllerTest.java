package com.xbb.settlement;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class SettlementControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 未带token支付被拒绝() throws Exception {
        mvc.perform(put("/api/settlement/999999/pay"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 不存在的结算记录支付返回400() throws Exception {
        // 结算接口目前是"authenticated 即可操作"的粗粒度鉴权,随便借一个合法登录态即可
        String token = tokenFor("13100000099");

        mvc.perform(put("/api/settlement/999999/pay").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private String tokenFor(String phone) throws Exception {
        String codeBody = mvc.perform(post("/api/identity/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\"}".formatted(phone)))
                .andReturn().getResponse().getContentAsString();
        String code = json.readTree(codeBody).get("code").asText();

        String loginBody = mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(loginBody).get("token").asText();
    }
}
