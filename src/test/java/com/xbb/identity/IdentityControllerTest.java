package com.xbb.identity;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class IdentityControllerTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired TestCodeAccessor codes;
    @Autowired ObjectMapper json;

    @Test
    void 申请验证码时绝不把验证码回给调用方() throws Exception {
        // 这个端点是 permitAll 的。回显验证码 = 任何人报一个手机号就能登录那个账号,
        // 包括平台管理员,整套鉴权与 RBAC 直接失效。修复前它就是这么写的。
        mvc.perform(post("/api/identity/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13700000001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist());
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

    @Test
    void 并发绑定同一身份证只有一个成功另一个不是裸500() throws Exception {
        String tokenA = tokenFor("13700000004");
        String tokenB = tokenFor("13700000005");
        String sharedIdNumber = "110101199001018888";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());

        Runnable taskA = () -> verifyRealNameAsync(tokenA, "甲", sharedIdNumber, ready, go, statuses);
        Runnable taskB = () -> verifyRealNameAsync(tokenB, "乙", sharedIdNumber, ready, go, statuses);

        Thread t1 = new Thread(taskA);
        Thread t2 = new Thread(taskB);
        t1.start();
        t2.start();
        ready.await();
        go.countDown();
        t1.join();
        t2.join();

        // 铁律:不能出现"两个都绑上了"或者"任何一个是裸 500(即不在 {204,409} 里)"
        assertThat(statuses).hasSize(2);
        assertThat(statuses).allMatch(s -> s == 204 || s == 409);
        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
    }

    private void verifyRealNameAsync(String token, String realName, String idNumber,
                                      CountDownLatch ready, CountDownLatch go, List<Integer> statuses) {
        ready.countDown();
        try {
            go.await();
            int status = mvc.perform(put("/api/identity/real-name")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"realName\":\"%s\",\"idNumber\":\"%s\"}".formatted(realName, idNumber)))
                    .andReturn().getResponse().getStatus();
            statuses.add(status);
        } catch (Exception e) {
            statuses.add(-1);
        }
    }

    private String tokenFor(String phone) throws Exception {
        String code = codeFor(phone);
        String loginBody = mvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"%s\",\"code\":\"%s\"}".formatted(phone, code)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(loginBody).get("token").asText();
    }

    /**
     * 验证码从测试钩子取,不从 HTTP 响应取——那个端点是 permitAll 的,
     * 回显验证码等于任何人报个手机号就能登录任意账号(修复前正是如此)。
     */
    private String codeFor(String phone) {
        return codes.issue(phone);
    }
}
