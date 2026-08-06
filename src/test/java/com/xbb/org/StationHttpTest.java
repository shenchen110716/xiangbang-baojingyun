package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 建站、指派站长、按类目设费率、授权业务员的 HTTP 层。
 *
 * <p><b>为什么服务层测过了还要测这一遍。</b>考勤域和借支都栽在这里过 ——
 * 后端逻辑通了但没有控制器,界面上点不着,那条逻辑就永远不会触发,
 * 而测试全绿、界面也看不出异常。
 *
 * <p>号段 185,信用代码 …m xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class StationHttpTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired BrokerApi brokerApi;

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
        ops.userId();
        String phone = TestPlatformOps.Accessor.PHONE;
        return identityApi.loginByPhone(phone, codes.issue(phone)).token();
    }

    @Test
    void 建站到指派站长再到设费率_走真实HTTP() {
        String admin = opsToken();

        var created = send(HttpMethod.POST, "/api/org/stations", admin,
                "{\"name\":\"HTTP服务站\",\"creditCode\":\"9111000000000m01X\"}");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long orgId = Long.parseLong(created.getBody().replaceAll("\\D+", ""));

        // 建出来还没有站长
        assertThat(get("/api/org/" + orgId, admin).getBody()).contains("\"legalRepUserId\":null");

        Token master = verified("18500000001", "站长", "110101199001070001");
        // 实名副本异步到达组织域,不等的话会得到"新站长未实名认证"
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(send(HttpMethod.PUT, "/api/org/stations/" + orgId + "/master", admin,
                        "{\"userId\":%d,\"reason\":\"首次指派\"}".formatted(master.userId()))
                        .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT));

        assertThat(get("/api/org/stations/" + orgId + "/master-changes", admin).getBody())
                .contains("首次指派");

        // 服务站副本到经纪人域后才能设费率
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));
        assertThat(send(HttpMethod.PUT, "/api/broker/rates", admin, """
                {"stationOrgId":%d,"category":"JOB","percent":50,"reason":"重点站点"}"""
                .formatted(orgId)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/broker/rates/" + orgId, admin).getBody()).contains("50");

        // 平台默认那条要排在 /{orgId} 之前,否则 "defaults" 会被当成路径变量
        assertThat(send(HttpMethod.PUT, "/api/broker/rates", admin,
                "{\"stationOrgId\":null,\"category\":\"PRODUCT\",\"percent\":45,\"reason\":\"商品毛利更高\"}")
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get("/api/broker/rates/defaults", admin).getBody()).contains("PRODUCT").contains("45");
    }

    @Test
    void 分享到授权业务员_走真实HTTP() {
        String admin = opsToken();
        long orgId = Long.parseLong(send(HttpMethod.POST, "/api/org/stations", admin,
                "{\"name\":\"授权HTTP站\",\"creditCode\":\"9111000000000m02X\"}")
                .getBody().replaceAll("\\D+", ""));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));

        Token staff = verified("18500000002", "员工", "110101199001070002");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(send(HttpMethod.POST, "/api/broker/salesmen/grant", admin,
                        "{\"stationOrgId\":%d,\"userId\":%d}".formatted(orgId, staff.userId()))
                        .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT));

        assertThat(get("/api/broker/salesmen/" + staff.userId() + "/origin", staff.token()).getBody())
                .contains("STATION_GRANT");

        // 分享:同一个人重复分享同一个东西返回同一个码
        String first = send(HttpMethod.POST, "/api/broker/shares", staff.token(),
                "{\"targetType\":\"JOB\",\"targetId\":42}").getBody();
        String again = send(HttpMethod.POST, "/api/broker/shares", staff.token(),
                "{\"targetType\":\"JOB\",\"targetId\":42}").getBody();
        assertThat(first).isEqualTo(again);
    }

    @Test
    void 少填必填项返回400而不是把人踢下线() {
        String admin = opsToken();
        // 401 会让前端清登录态 —— 人少填一个字段就被踢出去
        assertThat(send(HttpMethod.POST, "/api/org/stations", admin, "{\"name\":\"\",\"creditCode\":\"\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        long orgId = Long.parseLong(send(HttpMethod.POST, "/api/org/stations", admin,
                "{\"name\":\"校验站\",\"creditCode\":\"9111000000000m03X\"}")
                .getBody().replaceAll("\\D+", ""));
        // 换站长必须填原因
        assertThat(send(HttpMethod.PUT, "/api/org/stations/" + orgId + "/master", admin,
                "{\"userId\":1,\"reason\":\"\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 比例越界
        assertThat(send(HttpMethod.PUT, "/api/broker/rates", admin,
                "{\"stationOrgId\":null,\"category\":\"JOB\",\"percent\":101,\"reason\":\"过高\"}")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 不是平台运维建不了站也换不了站长() {
        Token outsider = verified("18500000003", "路人", "110101199001070003");
        assertThat(send(HttpMethod.POST, "/api/org/stations", outsider.token(),
                "{\"name\":\"野站\",\"creditCode\":\"9111000000000m04X\"}").getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);

        long orgId = Long.parseLong(send(HttpMethod.POST, "/api/org/stations", opsToken(),
                "{\"name\":\"防越权站\",\"creditCode\":\"9111000000000m05X\"}")
                .getBody().replaceAll("\\D+", ""));
        assertThat(send(HttpMethod.PUT, "/api/org/stations/" + orgId + "/master", outsider.token(),
                "{\"userId\":%d,\"reason\":\"自己上位\"}".formatted(outsider.userId())).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
    }

    @Test
    void 未登录一律401() {
        HttpHeaders anon = new HttpHeaders();
        anon.setContentType(MediaType.APPLICATION_JSON);
        for (String p : new String[]{"/api/broker/rates/defaults", "/api/broker/rates/1",
                "/api/org/stations/1/master-changes", "/api/broker/salesmen/1/origin"}) {
            assertThat(http.exchange(p, HttpMethod.GET, new HttpEntity<>(anon), String.class).getStatusCode())
                    .as("未登录访问 " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
