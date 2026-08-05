package com.xbb.web;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
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
 * 合作、操作员、分配方案、微信登录的 HTTP 层。
 *
 * <p><b>为什么服务层测过了还要测这一遍。</b>这个项目里"后端写了但接不上"
 * 已经出现过四次(考勤、借支、联合、服务站):逻辑通了但没有控制器,
 * 界面点不着,那条逻辑永远不会触发,而测试全绿、界面也看不出异常。
 *
 * <p>判据是**未登录返回 401 而不是 404** —— 404 说明端点根本没注册。
 *
 * <p>号段 190。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class CooperationHttpTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;

    private HttpHeaders anon() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders authed(String phone) {
        HttpHeaders h = anon();
        h.setBearerAuth(identityApi.loginByPhone(phone, codes.issue(phone)).token());
        return h;
    }

    @Test
    void 新端点都注册了_未登录返回401而不是404() {
        for (String p : new String[]{
                "/api/broker/cooperations/org/1", "/api/broker/cooperations/1/operators",
                "/api/broker/schemes/defaults", "/api/broker/schemes/1",
                "/api/identity/wechat/bound"}) {
            assertThat(http.exchange(p, HttpMethod.GET, new HttpEntity<>(anon()), String.class)
                    .getStatusCode())
                    .as("未登录访问 " + p + " —— 404 说明端点根本没注册")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void 微信登录匿名可达_没绑过时不建账号() {
        // **登录本身要求先登录在逻辑上不成立。**这条守的是那个放行没被漏掉
        var r = http.exchange("/api/identity/wechat/login", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"http-test-code\"}", anon()), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 没绑过 → userId=0,引导去手机登录后绑定
        assertThat(r.getBody()).contains("\"userId\":0").contains("\"newUser\":true");
    }

    @Test
    void 绑定微信必须先登录() {
        // **只放 login 不放 bind。**放开的话任何人都能把自己的微信绑到别人的账号上
        assertThat(http.exchange("/api/identity/wechat/bind", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"x\"}", anon()), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 登录后能绑定并查到已绑() {
        HttpHeaders h = authed("19000000001");
        assertThat(http.exchange("/api/identity/wechat/bind", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"bind-me\"}", h), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.exchange("/api/identity/wechat/bound", HttpMethod.GET,
                new HttpEntity<>(h), String.class).getBody()).contains("true");
    }

    @Test
    void 少填必填项返回400而不是把人踢下线() {
        HttpHeaders h = authed("19000000002");
        // 401 会让前端清登录态 —— 人少填一个字段就被踢出去
        assertThat(http.exchange("/api/identity/wechat/bind", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"\"}", h), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/broker/cooperations", HttpMethod.POST,
                new HttpEntity<>("{\"stationOrgId\":0,\"partnerOrgId\":0}", h), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
