package com.xbb.web;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误响应状态码的守卫。
 *
 * <p>起因:线上打真实端点时发现「改参数没填理由」返回 **401** 而不是 400。
 * 根因是一整类问题——未被 {@code @ExceptionHandler} 截住的异常会被转发到 /error,
 * 而 {@code OncePerRequestFilter} 默认不跑 ERROR 分派,那次请求在鉴权看来是未认证的,
 * 于是被 {@code anyRequest().authenticated()} 挡成 401。
 *
 * <p>后果不是"消息不好看":前端见 401 会清掉登录态,
 * **用户少填一个必填字段就被踢下线**,提示还是"登录已失效"。
 *
 * <p><b>为什么这个类不用 MockMvc。</b>第一版用了,结果**把修复停掉它照样全绿** ——
 * {@code MethodArgumentNotValidException} 是 Spring MVC 的标准异常,
 * MockMvc 里由 {@code DefaultHandlerExceptionResolver} 直接转成 400,
 * 根本走不到 /error 转发,也就碰不到安全过滤链。
 * 只有起真实容器、发真实 HTTP 请求才复现得了 —— 铁律 7 那句
 * "它测的路径和生产跑的路径是同一条吗",这里答案曾经是"不是"。
 *
 * <p>号段 170。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ErrorStatusTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    private HttpHeaders authed(String phone) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(identityApi.loginByPhone(phone, codes.issue(phone)).token());
        return h;
    }

    private HttpHeaders anonymous() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpStatusCode post(String path, HttpHeaders h, String body) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class).getStatusCode();
    }

    private HttpStatusCode get(String path, HttpHeaders h) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode();
    }

    @Test
    void 已登录时字段校验失败返回400而不是401() {
        // 401 会让前端清登录态。"填错了"和"没登录"必须是两件事。
        assertThat(post("/api/org", authed("17000000001"),
                "{\"type\":\"FACTORY\",\"name\":\"\",\"creditCode\":\"\"}"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 已登录时请求体不是合法JSON返回400() {
        assertThat(post("/api/org", authed("17000000002"), "{ 这不是 JSON"))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 放行ERROR分派没有把鉴权弄出洞_未登录仍然401() {
        // 上面那个修复的**反向守卫**:permitAll 给了 ERROR 分派,
        // 必须证明它没有顺带放开正常请求。少了这条,一次手滑就能把整个鉴权敞开。
        for (String p : new String[]{"/api/org/mine", "/api/settlement/mine",
                "/api/fund/payouts/mine", "/api/ops/settings", "/api/identity/roles"}) {
            assertThat(get(p, anonymous())).as("%s 未登录时", p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void 未登录且请求体非法时仍然是401而不是400() {
        // 顺序很重要:鉴权要在参数校验**之前**。
        // 反过来的话,未登录的人能通过错误码探出"这个字段叫什么、什么格式"。
        assertThat(post("/api/org", anonymous(), "{\"name\":\"\"}"))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 静态资源与健康检查仍然匿名可达() {
        assertThat(get("/actuator/health", anonymous())).isEqualTo(HttpStatus.OK);
    }
}
