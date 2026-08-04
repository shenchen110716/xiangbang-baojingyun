package com.xbb.identity;

import com.xbb.TestcontainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 取验证码的开发端点的守卫。
 *
 * <p>这个端点存在的唯一理由是:公开的 {@code /api/identity/code} **不能**回显验证码
 * (那个洞真的存在过并被修掉了),而没有真实短信通道时又得有办法登录。
 * 于是把"能拿到码"这件事关进一道要口令的门里。
 *
 * <p>门要是形同虚设,等于把修掉的洞原样放回来,所以下面每一条都必须真的守住。
 */
@SpringBootTest(properties = "xbb.dev.code-token=" + DevCodeControllerTest.TOKEN)
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DevCodeControllerTest {

    static final String TOKEN = "test-dev-token-0123456789";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String PHONE = "13710000001";

    @Test
    void 带正确口令能拿到验证码并且这个码真的能登录() throws Exception {
        String body = mvc.perform(post("/api/identity/dev/code")
                        .header("X-Dev-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String code = json.readValue(body, Map.class).get("code").toString();
        assertThat(code).hasSize(6);

        // 关键:不只断言"拿到了一串东西",而要证明它**真的能换到 token**。
        // 只验前半段的话,返回一个假码也能让测试通过,而登录仍然是坏的。
        mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PHONE + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 不带口令拿不到() throws Exception {
        mvc.perform(post("/api/identity/dev/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13710000002\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 口令错误拿不到() throws Exception {
        mvc.perform(post("/api/identity/dev/code")
                        .header("X-Dev-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13710000003\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 公开端点仍然不回显验证码() throws Exception {
        // 加了开发端点之后,原来那条守卫必须还在。
        // 这个项目里"改 A 顺手把 B 弄坏了、而 B 没人看着"出现过不止一次。
        String body = mvc.perform(post("/api/identity/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13710000004\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("code");
    }
}
