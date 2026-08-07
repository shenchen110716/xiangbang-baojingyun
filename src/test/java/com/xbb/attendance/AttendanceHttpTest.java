package com.xbb.attendance;

import com.xbb.TestcontainersConfig;
import com.xbb.attendance.internal.EngagedWorkerRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.settlement.internal.SettlementPostedJobRepository;
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
 * 考勤与计薪方案的 HTTP 层守卫。
 *
 * <p><b>为什么专门为控制器写一遍。</b>这两个域的服务层早就测过了,但此前**没有控制器** ——
 * 工时录不进来,计薪就一直走"没有方案"的退回分支,按岗位一口价发钱。
 * 界面上看不出任何异常,测试也全绿,因为测试是直接调 API 的。
 * 铁律 7 那句"它测的路径和生产跑的路径是同一条吗",这里答案曾经是"不是"。
 *
 * <p>所以这个类走真实 HTTP:发请求、带 JWT、看状态码。守三件事 ——
 * 端点通、**别人的考勤看不到**、参数错了返回 400 而不是把人踢下线。
 *
 * <p>号段 177,信用代码 …d xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class AttendanceHttpTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired TestRestTemplate http;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired EngagedWorkerRepository engagedWorkers;
    @Autowired SettlementPostedJobRepository postedJobs;

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

    private record Scene(Token boss, Token worker, long jobId, long applicationId) { }

    private Scene scene(String suffix, String code) {
        Token boss = verified("1770000" + suffix + "1", "老板", "1101011990010114" + suffix);
        Token worker = verified("1770000" + suffix + "2", "工人", "1101011990010115" + suffix);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "考勤HTTP厂" + code, code, boss.userId())));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "考勤HTTP岗", "描述", 20_000L, boss.userId())));
        long jobId = jobH.get();
        await().atMost(Duration.ofSeconds(20)).until(() -> postedJobs.findById(jobId).isPresent());

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appH.set(engagementApi.apply(jobId, worker.userId())));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss.userId()));
        await().atMost(Duration.ofSeconds(20)).until(() -> engagedWorkers.findById(appId).isPresent());
        return new Scene(boss, worker, jobId, appId);
    }

    @Test
    void 录入到确认再到汇总_走真实HTTP() {
        Scene s = scene("01", "9111000000000d01X");

        var created = send(HttpMethod.POST, "/api/attendance", s.boss().token(), """
                {"applicationId":%d,"workDate":"2026-07-01","minutes":480,
                 "source":"MANUAL","remark":"白班","reason":"手工录入"}""".formatted(s.applicationId()));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        long workdayId = Long.parseLong(created.getBody().replaceAll("\\D+", ""));

        // 还是草稿 —— **草稿不该计入工资**
        assertThat(get("/api/attendance/application/" + s.applicationId() + "/summary", s.boss().token())
                .getBody()).contains("\"minutes\":0");

        assertThat(send(HttpMethod.PUT, "/api/attendance/" + workdayId + "/confirm", s.boss().token(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var summary = get("/api/attendance/application/" + s.applicationId() + "/summary", s.boss().token());
        assertThat(summary.getBody()).contains("\"minutes\":480").contains("\"workDays\":1");

        // 工人看得到自己的
        assertThat(get("/api/attendance/mine?from=2026-06-01&to=2026-07-31", s.worker().token()).getBody())
                .contains("2026-07-01").contains("CONFIRMED");
    }

    @Test
    void 别人的考勤看不到也录不了() {
        Scene s = scene("02", "9111000000000d02X");
        Token outsider = verified("17700000099", "路人", "110101199001011499");

        // 录:不是这个岗位的雇主,不该能给别人的工人记工时
        assertThat(send(HttpMethod.POST, "/api/attendance", outsider.token(), """
                {"applicationId":%d,"workDate":"2026-07-02","minutes":480,
                 "source":"MANUAL","reason":"越权"}""".formatted(s.applicationId())).getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);

        // 看明细。**2026-08-07 起回 200 [] 而不是报错**(铁律 5.1):
        // 报"只有组织法人代表可以…"等于确认了这个编号存在,
        // 顺着编号一个个试就能摸清哪些是真的
        var detail = get("/api/attendance/application/" + s.applicationId(), outsider.token());
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).isEqualTo("[]");

        // **汇总也要挡。**它只回两个数字,很容易被当成"不敏感"而漏掉归属校验 ——
        // 但那两个数字是别人的工时
        // **2026-08-07 起回 404**:不可见就当不存在(铁律 5.1)。
        // 更要紧的是它现在**显式判断归属**,而不是借道 listByApplication 的异常 ——
        // 那条旁路在 listByApplication 改成"回空列表"的当天就通了
        assertThat(get("/api/attendance/application/" + s.applicationId() + "/summary", outsider.token())
                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 工人自己的列表里不该出现别人的记录
        assertThat(get("/api/attendance/mine?from=2026-01-01&to=2026-12-31", outsider.token()).getBody())
                .isEqualTo("[]");
    }

    @Test
    void 少填必填项返回400而不是把人踢下线() {
        Scene s = scene("03", "9111000000000d03X");
        // reason 是必填的:订正工时必须留下为什么。
        // 但漏填只该是 400 —— 返回 401 的话前端会清登录态,人少填一个字段就被踢出去
        assertThat(send(HttpMethod.POST, "/api/attendance", s.boss().token(), """
                {"applicationId":%d,"workDate":"2026-07-03","minutes":480,"source":"MANUAL"}"""
                .formatted(s.applicationId())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(send(HttpMethod.POST, "/api/attendance", s.boss().token(), """
                {"applicationId":%d,"workDate":"2026-07-03","minutes":-60,
                 "source":"MANUAL","reason":"负工时"}""".formatted(s.applicationId())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 批量导入里一条坏行不该毁掉整批() {
        Scene s = scene("04", "9111000000000d04X");
        // 第二行的履约单不存在。整体仍是 200,逐条带 error —— 否则一个错行毁掉一整月的导入
        var r = send(HttpMethod.POST, "/api/attendance/batch", s.boss().token(), """
                {"rows":[{"applicationId":%d,"workDate":"2026-07-05","minutes":480},
                         {"applicationId":88888888,"workDate":"2026-07-05","minutes":480}],
                 "source":"IMPORT","reason":"月度导入"}""".formatted(s.applicationId()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"created\":true").contains("\"created\":false");

        // 好的那行真的进去了
        assertThat(get("/api/attendance/application/" + s.applicationId(), s.boss().token()).getBody())
                .contains("2026-07-05");
    }

    @Test
    void 计薪方案发布与查询_走真实HTTP() {
        Scene s = scene("05", "9111000000000d05X");

        assertThat(send(HttpMethod.POST, "/api/settlement/job/" + s.jobId() + "/pay-plan", s.boss().token(), """
                {"name":"初版","payType":"HOURLY","basicSalaryCents":2500,"floatSalaryCents":500,
                 "fixedSalaryCents":0,"effectiveFrom":"2026-07-01",
                 "factors":[{"factorType":"BONUS","name":"全勤奖","amountCents":10000}]}""")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        var active = get("/api/settlement/job/" + s.jobId() + "/pay-plan/active", s.boss().token());
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getBody()).contains("全勤奖").contains("\"version\":1");

        // 改版:发新版本,旧版失效但仍查得到
        send(HttpMethod.POST, "/api/settlement/job/" + s.jobId() + "/pay-plan", s.boss().token(), """
                {"name":"调薪版","payType":"HOURLY","basicSalaryCents":3000,"floatSalaryCents":800,
                 "fixedSalaryCents":0,"effectiveFrom":"2026-08-01","factors":[]}""");

        String all = get("/api/settlement/job/" + s.jobId() + "/pay-plans", s.boss().token()).getBody();
        assertThat(all).contains("初版").contains("调薪版");
        // 同时只有一个生效 —— 两个都 ACTIVE 的话算薪时不知道用哪个
        assertThat(all.split("\"status\":\"ACTIVE\"", -1).length - 1).isEqualTo(1);
    }

    @Test
    void 没有方案时查生效方案返回204而不是报错() {
        Scene s = scene("06", "9111000000000d06X");
        // 没方案是正常状态(按岗位一口价发),不是错误。
        // 报 404/500 会让前端把它当故障显示,而真正的故障就淹没在里面了
        assertThat(get("/api/settlement/job/" + s.jobId() + "/pay-plan/active", s.boss().token())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void 不是法人代表不能设方案() {
        Scene s = scene("07", "9111000000000d07X");
        Token outsider = verified("17700000098", "路人", "110101199001011498");
        assertThat(send(HttpMethod.POST, "/api/settlement/job/" + s.jobId() + "/pay-plan", outsider.token(), """
                {"name":"越权","payType":"HOURLY","basicSalaryCents":2500,"floatSalaryCents":0,
                 "fixedSalaryCents":0,"effectiveFrom":"2026-07-01","factors":[]}""").getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT, HttpStatus.BAD_REQUEST);
    }

    @Test
    void 未登录一律401() {
        // 新加的端点很容易漏进 permitAll 名单。这条是那种手滑的守卫
        HttpHeaders anon = new HttpHeaders();
        anon.setContentType(MediaType.APPLICATION_JSON);
        for (String p : new String[]{"/api/attendance/mine", "/api/attendance/application/1",
                "/api/attendance/application/1/summary", "/api/settlement/job/1/pay-plans",
                "/api/settlement/job/1/pay-plan/active"}) {
            assertThat(http.exchange(p, HttpMethod.GET, new HttpEntity<>(anon), String.class).getStatusCode())
                    .as("未登录访问 " + p).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
