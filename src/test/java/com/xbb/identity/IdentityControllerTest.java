package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class IdentityControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 请求验证码再登录_返回token() throws Exception {
        mvc.perform(post("/api/identity/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13700000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test
    void 验证码错误返回400() throws Exception {
        mvc.perform(post("/api/identity/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"13700000002\"}"));

        mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13700000002\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 未带token访问实名认证接口被拒绝() throws Exception {
        mvc.perform(put("/api/identity/real-name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"张三\",\"idNumber\":\"110101199001017777\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 带上登录token只能实名认证token对应的自己() throws Exception {
        String phone = "13700000003";
        String code = codeFor(phone);
        String loginBody = mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(loginBody).get("token").asText();

        // 请求体里已经没有 userId 字段了——身份完全来自 token,不再由客户端指定
        mvc.perform(put("/api/identity/real-name")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"张三\",\"idNumber\":\"110101199001017777\"}"))
                .andExpect(status().isNoContent());
    }

    private String codeFor(String phone) throws Exception {
        String body = mvc.perform(post("/api/identity/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\"}".formatted(phone)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asText();
    }
}
