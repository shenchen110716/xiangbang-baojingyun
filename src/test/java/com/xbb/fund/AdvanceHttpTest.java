package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 借支与联合服务站的 HTTP 层。
 *
 * <p><b>为什么服务层测过了还要测这一遍。</b>考勤域就是只有 API 没有控制器 ——
 * 工时录不进来,计薪永远走"没有方案"的退回分支,界面看不出异常、测试全绿,
 * 而工资是按一口价发的。借支同理:抵扣逻辑接在放款路径上是通的,
 * 但**没人能批出一笔借支**,那条逻辑就永远不会触发。
 *
 * <p>号段 182,信用代码 …i xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class AdvanceHttpTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired com.xbb.broker.api.BrokerApi brokerApi;

    private record Token(long userId, String token) { }

    private Token verified(String phone, String name, String idNo) {
        var login = identityApi.loginByPhone(phone, codes.issue(phone));
        identityApi.verifyRealName(login.userId(), name, idNo);
        return new Token(login.userId(), login.token());
    }

    private HttpHeaders authed(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private ResponseEntity<String> send(HttpMethod m, String path, String token, String body) {
        return http.exchange(path, m, new HttpEntity<>(body, authed(token)), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(authed(token)), String.class);
    }

    private String opsToken() {
        ops.userId();   // 先确保角色已授予
        String phone = TestPlatformOps.Accessor.PHONE;
        return identityApi.loginByPhone(phone, codes.issue(phone)).token();
    }

    // ─────────────── 借支 ───────────────

    @Test
    void 批借支到登记还款走真实HTTP() {
        Token worker = verified("18200000001", "工人", "110101199001040001");
        String admin = opsToken();

        var created = send(HttpMethod.POST, "/api/fund/advances", admin, """
                {"workerUserId":%d,"amountCents":50000,"reason":"家中急用"}"""
                .formatted(worker.userId()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = Long.parseLong(created.getBody().replaceAll("\\D+", ""));

        // 工人自己看得到,并且知道还欠多少 —— 否则钱少了只能来问人
        assertThat(get("/api/fund/advances/mine", worker.token()).getBody()).contains("家中急用");
        assertThat(get("/api/fund/advances/mine/outstanding", worker.token()).getBody())
                .contains("50000");

        assertThat(send(HttpMethod.POST, "/api/fund/advances/" + id + "/repayments", admin,
                "{\"amountCents\":20000}").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/fund/advances/mine/outstanding", worker.token()).getBody())
                .contains("30000");

        // 明细查得到,争议时靠它说话
        assertThat(get("/api/fund/advances/" + id + "/repayments", admin).getBody())
                .contains("MANUAL").contains("20000");
    }

    @Test
    void 不是平台运维批不了借支() {
        Token worker = verified("18200000002", "工人", "110101199001040002");
        Token outsider = verified("18200000003", "路人", "110101199001040003");
        // 借支是平台垫钱。少了这条,谁都能给自己批钱
        assertThat(send(HttpMethod.POST, "/api/fund/advances", outsider.token(), """
                {"workerUserId":%d,"amountCents":50000,"reason":"自己批"}"""
                .formatted(worker.userId())).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
    }

    @Test
    void 少填事由返回400而不是把人踢下线() {
        Token worker = verified("18200000004", "工人", "110101199001040004");
        // 返回 401 的话前端会清登录态 —— 人少填一个字段就被踢出去
        assertThat(send(HttpMethod.POST, "/api/fund/advances", opsToken(), """
                {"workerUserId":%d,"amountCents":50000}""".formatted(worker.userId()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, "/api/fund/advances", opsToken(), """
                {"workerUserId":%d,"amountCents":-100,"reason":"负数"}""".formatted(worker.userId()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 路人拿不到别人的借支() {
        Token worker = verified("18200000005", "工人", "110101199001040005");
        Token outsider = verified("18200000006", "路人", "110101199001040006");
        String admin = opsToken();
        var created = send(HttpMethod.POST, "/api/fund/advances", admin, """
                {"workerUserId":%d,"amountCents":50000,"reason":"借支事由"}"""
                .formatted(worker.userId()));
        long id = Long.parseLong(created.getBody().replaceAll("\\D+", ""));

        // 借支金额说明这个人缺钱 —— 不该给无关的人看(铁律 5.1)
        assertThat(get("/api/fund/advances/" + id, outsider.token()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/fund/advances/worker/" + worker.userId(), outsider.token()).getBody())
                .isEqualTo("[]");
        // 正主看得到 —— 挡住路人不能连正主一起挡掉
        assertThat(get("/api/fund/advances/" + id, worker.token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ─────────────── 联合服务站 ───────────────

    private record Sta(long orgId, Token legalRep) { }

    private Sta station(String phone, String idNo, String name, String creditCode) {
        Token legalRep = verified(phone, "站长", idNo);
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.SERVICE_STATION, name, creditCode, legalRep.userId())));
        long orgId = h.get();
        orgApi.approve(orgId, ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(st -> st.orgId() == orgId));
        return new Sta(orgId, legalRep);
    }

    @Test
    void 联合申请到确认走真实HTTP() {
        Sta a = station("18200000011", "110101199001040011", "HTTP甲站", "9111000000000i01X");
        Sta b = station("18200000012", "110101199001040012", "HTTP乙站", "9111000000000i02X");

        var created = send(HttpMethod.POST, "/api/broker/joints", a.legalRep().token(), """
                {"fromOrgId":%d,"toOrgId":%d,"ratePercent":30}""".formatted(a.orgId(), b.orgId()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long id = Long.parseLong(created.getBody().replaceAll("\\D+", ""));

        assertThat(get("/api/broker/joints/station/" + a.orgId(), a.legalRep().token()).getBody())
                .contains("PENDING");

        // **发起方自己确认不了** —— 否则那个两步流程形同虚设
        assertThat(send(HttpMethod.PUT, "/api/broker/joints/" + id + "/confirm",
                a.legalRep().token(), null).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.PUT, "/api/broker/joints/" + id + "/confirm",
                b.legalRep().token(), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/broker/joints/station/" + b.orgId(), b.legalRep().token()).getBody())
                .contains("ACTIVE");
    }

    @Test
    void 联合比例越界返回400() {
        Sta a = station("18200000013", "110101199001040013", "越界甲站", "9111000000000i03X");
        Sta b = station("18200000014", "110101199001040014", "越界乙站", "9111000000000i04X");
        for (int rate : new int[]{0, 100, -5}) {
            assertThat(send(HttpMethod.POST, "/api/broker/joints", a.legalRep().token(), """
                    {"fromOrgId":%d,"toOrgId":%d,"ratePercent":%d}"""
                    .formatted(a.orgId(), b.orgId(), rate)).getStatusCode())
                    .as("比例 " + rate + " 应当被拒").isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void 未登录一律401() {
        // 新加的端点很容易漏进 permitAll 名单
        HttpHeaders anon = new HttpHeaders();
        anon.setContentType(MediaType.APPLICATION_JSON);
        for (String p : new String[]{"/api/fund/advances/mine", "/api/fund/advances/1",
                "/api/fund/advances/mine/outstanding", "/api/broker/joints/station/1"}) {
            assertThat(http.exchange(p, HttpMethod.GET, new HttpEntity<>(anon), String.class).getStatusCode())
                    .as("未登录访问 " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
