package com.xbb.profile;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ProfileControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 未带token提交标签被拒绝() throws Exception {
        mvc.perform(post("/api/profile/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"普工\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带token提交受控词表标签成功() throws Exception {
        String token = tokenFor("15100000099");

        mvc.perform(post("/api/profile/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"普工\"]}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/profile/tags").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void 提交词表外标签返回400() throws Exception {
        String token = tokenFor("15100000098");

        mvc.perform(post("/api/profile/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"拧螺丝的\"]}"))
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
