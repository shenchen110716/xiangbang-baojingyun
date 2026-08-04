package com.xbb.settlement;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.attendance.internal.EngagedWorkerRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.internal.SettlementPostedJobRepository;
import com.xbb.settlement.internal.SettlementRepository;
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
 * 工资单的可见性。**别人的工资不该看得到。**
 *
 * <p>起因:做工资条时顺手看了一眼 {@code GET /api/settlement/{id}},
 * 发现它既不接 caller 也不做归属校验 —— 任何登录用户把 id 从 1 数上去,
 * 就能拿到全站每个人的工资金额、工人编号、岗位编号。
 * 别的接口都守着(listMySettlements 是"查询条件即归属"),唯独这条按 id 直查的漏了。
 *
 * <p>这类洞不会有人报错:请求成功、页面正常,只有专门去数 id 才发现得了。
 *
 * <p>号段 178,信用代码 …e xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class SettlementAccessTest {

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
    @Autowired AgreementApi agreementApi;
    @Autowired SettlementApi settlementApi;
    @Autowired SettlementRepository settlements;
    @Autowired SettlementPostedJobRepository postedJobs;
    @Autowired EngagedWorkerRepository engagedWorkers;

    private record Token(long userId, String token) { }

    private Token verified(String phone, String name, String idNo) {
        var login = identityApi.loginByPhone(phone, codes.issue(phone));
        identityApi.verifyRealName(login.userId(), name, idNo);
        return new Token(login.userId(), login.token());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private record Scene(Token boss, Token worker, long settlementId) { }

    /** 一路跑到真的出了工资单。 */
    private Scene settled(String suffix, String code) {
        Token boss = verified("1780000" + suffix + "1", "老板", "1101011990010116" + suffix);
        Token worker = verified("1780000" + suffix + "2", "工人", "1101011990010117" + suffix);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "工资单可见性厂" + code, code, boss.userId())));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "工资单岗", "描述", 20_000L, boss.userId())));
        long jobId = jobH.get();
        await().atMost(Duration.ofSeconds(20)).until(() -> postedJobs.findById(jobId).isPresent());

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appH.set(engagementApi.apply(jobId, worker.userId())));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss.userId()));
        await().atMost(Duration.ofSeconds(20)).until(() -> engagedWorkers.findById(appId).isPresent());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(appId, worker.userId(), "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(appId, boss.userId()));

        long settlementId = await().atMost(Duration.ofSeconds(25))
                .until(() -> settlements.findByApplicationId(appId).map(s -> s.getId()).orElse(null),
                        java.util.Objects::nonNull);
        return new Scene(boss, worker, settlementId);
    }

    @Test
    void 路人按编号直查拿不到别人的工资单() {
        Scene s = settled("01", "9111000000000e01X");
        Token outsider = verified("17800000099", "路人", "110101199001011699");

        var r = get("/api/settlement/" + s.settlementId(), outsider.token());
        assertThat(r.getStatusCode())
                .as("按 id 直查别人的工资单必须被挡下 —— 不然把 id 从 1 数上去就是全站工资表")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        // 就算状态码将来变了,金额也绝不能出现在响应体里
        assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain("20000");
    }

    @Test
    void 工人本人和用人单位看得到() {
        Scene s = settled("02", "9111000000000e02X");
        // 挡住路人不能连正主一起挡掉 —— 那就成了"安全但没用"
        assertThat(get("/api/settlement/" + s.settlementId(), s.worker().token()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/settlement/" + s.settlementId(), s.boss().token()).getStatusCode())
                .as("用人单位要能核对自己发出去的工资").isEqualTo(HttpStatus.OK);
    }

    @Test
    void 工资条带明细_能解释金额怎么来的() {
        Scene s = settled("03", "9111000000000e03X");
        var r = get("/api/settlement/" + s.settlementId() + "/payslip", s.worker().token());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 这单没有计薪方案,走的是岗位一口价退回分支。
        // **这种情况也要能解释** —— 否则工人看到一个光秃秃的数字,对不上只能来问
        assertThat(r.getBody()).contains("20000").contains("lines");
    }

    @Test
    void 路人拿不到工资条明细() {
        Scene s = settled("04", "9111000000000e04X");
        Token outsider = verified("17800000098", "路人", "110101199001011698");
        var r = get("/api/settlement/" + s.settlementId() + "/payslip", outsider.token());
        assertThat(r.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain("20000");
    }
}
