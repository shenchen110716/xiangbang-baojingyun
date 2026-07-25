package com.xbb.fund;

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
class FundControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 未带token发放被拒绝() throws Exception {
        mvc.perform(put("/api/fund/payouts/999999/disburse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 不存在的发放记录返回400() throws Exception {
        String token = tokenFor("13400000099");

        mvc.perform(put("/api/fund/payouts/999999/disburse").header("Authorization", "Bearer " + token))
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
