package com.xbb.web;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 小程序首页那条链路,**走真实 HTTP**。
 *
 * <p>为什么单独要这一条:小程序侧的测试全是拿假响应喂给映射函数,
 * 它们证明"如果后端这么回,我就这么显示",**证明不了后端真的这么回**。
 * 字段名拼错一个字母,两边的单元测试都是绿的,而真机上是一片空白 ——
 * 这个项目里「后端写了但接不上」已经出现过四次。
 *
 * <p>号段 13003,信用代码 …v xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class MiniprogramJobFeedTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    @Test
    void 首页岗位列表带着单位名称与工作地点() {
        String phone = "13003000001";
        long rep = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(rep, "法人", "110101199001100001");

        AtomicLong org = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                org.set(orgApi.submitWithAddress(OrgType.FACTORY, "端到端测试厂",
                        "9111000000000v01X", rep, "苏州市姑苏区注册地址")));
        orgApi.approve(org.get(), ops.userId());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                jobApi.postJob(org.get(), "端到端钢筋工", "绑扎钢筋", 3000, 3,
                        "苏州市吴中区 3 号工地", rep));

        String seeker = "13003000002";
        String token = identityApi.loginByPhone(seeker, codes.issue(seeker)).token();

        // 副本经 outbox 异步到达岗位域,单位名称要等它
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            ResponseEntity<String> res = get("/api/job/open?limit=20", token);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = res.getBody();
            // **字段名逐个核对。**小程序读的就是这几个名字,
            // 拼错一个字母两边单元测试都绿,而真机上是空白
            assertThat(body).contains("\"orgName\":\"端到端测试厂\"");
            assertThat(body).contains("\"orgAddress\":\"苏州市姑苏区注册地址\"");
            assertThat(body).contains("\"workAddress\":\"苏州市吴中区 3 号工地\"");
            assertThat(body).contains("\"wageCents\":3000");
        });
    }

    /**
     * <b>当前:岗位列表要登录才能看。</b>这条测试记录的是现状,不是主张。
     *
     * <p>我原先想当然地断言它是公开的 —— 真跑一遍才发现是 401。
     * (铁律 5.1 里说的"岗位保持公开"指的是**已登录用户之间**不做归属过滤,
     * 不是"匿名可读"。这两件事我一开始混为一谈了。)
     *
     * <p><b>这对小程序是个真问题:</b>求职端第一屏就是岗位,而没绑过微信的新用户
     * 拿不到 token(后端对陌生 openid 不建账号),于是**第一屏永远是空的**,
     * 用户还没看到任何东西就被挡在门外。
     *
     * <p>要放开的话改 SecurityConfig 一行。但那是在放开一个访问控制,
     * **不该由我单方面决定** —— 等老板拍板。在那之前这条守住现状:
     * 有人顺手放开时它会变红,提醒那是一次访问控制变更,不是顺手改的小事。
     */
    @Test
    void 岗位列表当前需要登录() {
        ResponseEntity<String> res = get("/api/job/open?limit=5", null);
        assertThat(res.getStatusCode())
                .as("现状记录:未登录读岗位列表返回 401。要改成公开需明确决定")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
