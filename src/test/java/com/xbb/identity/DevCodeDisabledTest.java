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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 口令**没配置**时,取验证码的开发端点必须整个不可用。
 *
 * <p>单独一个测试类,是因为这条断言要求的是"属性不存在"这个状态,
 * 和 {@link DevCodeControllerTest} 的属性冲突,同一个上下文里表达不了。
 *
 * <p>为什么值得单独守:铁律 6 在这上面栽过——Spring 会拒绝**未设置**的变量,
 * 但**空字符串**是合法值。于是空口令能正常启动,并且和调用方传来的空口令匹配成功,
 * 门看着在、其实敞着。那次是 JWT 与商城券密钥,这次是同一个形态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DevCodeDisabledTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 没配口令时开发端点不可用_连空口令也进不去() throws Exception {
        // 不带头
        mvc.perform(post("/api/identity/dev/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13720000001\"}"))
                .andExpect(status().isNotFound());

        // 带空头 —— 这一条是重点:没有"空 == 空"的短路
        mvc.perform(post("/api/identity/dev/code")
                        .header("X-Dev-Token", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13720000002\"}"))
                .andExpect(status().isNotFound());
    }
}
