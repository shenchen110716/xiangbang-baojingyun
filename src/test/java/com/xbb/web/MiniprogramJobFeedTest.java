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
     * 岗位浏览对未登录开放(老板 2026-08-06 拍板)。
     *
     * <p><b>为什么必须开。</b>求职端第一屏就是岗位,而没绑过微信的新用户拿不到
     * token —— 后端对陌生 openid **不建账号**(那条是特意设计的:自动建号的话
     * 任何人扫一下就多一个没实名没归属的用户)。两条规则叠在一起,
     * 新用户第一屏永远是空的,还没看到任何东西就被挡在门外。
     *
     * <p><b>放开的范围就这两个,一个字都不能多。</b>它们不收 caller、
     * 回的是岗位标题/薪资/单位名/地址,不含任何个人信息。
     * 同在 /api/job 下的 /mine 和发岗必须仍然要登录 —— 下面逐条守住。
     */
    @Test
    void 未登录可以浏览岗位列表和详情() {
        assertThat(get("/api/job/open?limit=5", null).getStatusCode())
                .as("第一屏。挡住的话新用户什么都看不到")
                .isEqualTo(HttpStatus.OK);

        String phone = "13003000003";
        long rep = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(rep, "法人", "110101199001100003");
        AtomicLong org = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                org.set(orgApi.submitWithAddress(OrgType.FACTORY, "匿名可见厂",
                        "9111000000000v02X", rep, "地址")));
        orgApi.approve(org.get(), ops.userId());
        AtomicLong job = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                job.set(jobApi.postJob(org.get(), "匿名可见岗", "描述", 2000, rep)));

        // 列表能看、点进去看不了的话,等于没开
        assertThat(get("/api/job/" + job.get(), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void 放开的只有浏览_发岗和我的岗位仍然要登录() {
        // **这条才是那次放开的安全边界。**
        // 图省事写成 /api/job/** 的话,/mine 也会被放开 ——
        // 它靠 caller 取数,匿名进去要么 500 要么把别人的岗位列出来
        assertThat(get("/api/job/mine", null).getStatusCode())
                .as("我的岗位靠 caller 取数,绝不能匿名")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> posted = rest.exchange(url("/api/job"), HttpMethod.POST,
                new HttpEntity<>("{\"orgId\":1,\"title\":\"匿名发岗\",\"description\":\"x\",\"wageCents\":100}", h),
                String.class);
        assertThat(posted.getStatusCode())
                .as("匿名发岗必须挡住")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
