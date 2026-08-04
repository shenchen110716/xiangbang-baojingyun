package com.xbb.web;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.internal.CommissionRepository;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 全站「按编号直查」接口的越权体检。
 *
 * <p><b>为什么有这个类。</b>结算域那个洞({@code GET /api/settlement/{id}} 不校验归属,
 * 谁都能按编号翻别人工资)是做工资条时**偶然瞥见**的,不是查出来的。
 * 偶然发现说明没有机制 —— 同一类问题在别的域里有没有,当时谁也答不上来。
 *
 * <p>所以这个类把「登录了,但和这条数据毫无关系」的视角固定下来:
 * 一个路人拿着合法 token,去打每一个按编号直查的接口。**看得到就是洞。**
 *
 * <p>这类洞的共同点是不会有人报错:请求成功、页面正常、日志干净,
 * 只有专门拿别人的编号去试才发现得了。加新端点时最容易漏的也正是它 ——
 * 写的人满脑子是"我的页面要用",而不是"别人拿这个编号会怎样"。
 *
 * <p>号段 179,信用代码 …f xx。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class ObjectAccessAuditTest {

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
    @Autowired BrokerApi brokerApi;
    @Autowired FundApi fundApi;
    @Autowired SettlementRepository settlements;
    @Autowired CommissionRepository commissions;

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

    /**
     * 一条体检项:路人去打这个地址,期望看不到。
     *
     * <p>{@code list} 区分两种端点,**不是为了放宽标准**:
     * 单对象端点必须 404(返回 200 就等于把那条数据给了别人);
     * 列表端点返回 {@code 200 []} 是对的 —— 路人拿到的空数组
     * 和"这个编号根本不存在"的响应一模一样,他什么也没学到。
     * 把列表端点也要求 404,反而会逼出一个"存在但无权"的信号。
     */
    private record Probe(String path, String what, boolean list) {
        Probe(String path, String what) { this(path, what, false); }
    }

    @Test
    void 路人拿别人的编号_一个都不该看得到() {
        // ── 造一整条真实数据:组织、岗位、报名、协议、工资单、代发、佣金 ──
        Token boss   = verified("17900000001", "老板", "110101199001011801");
        Token worker = verified("17900000002", "工人", "110101199001011802");
        Token broker = verified("17900000003", "经纪人", "110101199001011803");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> brokerApi.registerBroker(broker.userId()));
        brokerApi.bindWorker(broker.userId(), worker.userId());

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "体检厂", "9111000000000f01X", boss.userId())));
        long orgId = orgH.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgId, "体检岗", "描述", 33_333L, boss.userId())));
        long jobId = jobH.get();

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appH.set(engagementApi.apply(jobId, worker.userId())));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(appId, worker.userId(), "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(appId, boss.userId()));

        long settlementId = await().atMost(Duration.ofSeconds(25))
                .until(() -> settlements.findByApplicationId(appId).map(s -> s.getId()).orElse(null),
                        java.util.Objects::nonNull);
        fundApi.topUp(AccountType.USER_FUNDS, 5_000_000, "体检备资");
        long payoutId = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).map(p -> p.id()).orElse(null),
                        java.util.Objects::nonNull);
        fundApi.disburse(payoutId, ops.userId());
        long commissionId = await().atMost(Duration.ofSeconds(25))
                .until(() -> commissions.findAllBySettlementId(settlementId).stream()
                                .filter(c -> c.getBrokerUserId() != null)
                                .map(c -> c.getId()).findFirst().orElse(null),
                        java.util.Objects::nonNull);

        // ── 路人登场:合法登录,但和上面每一条数据都毫无关系 ──
        Token outsider = verified("17900000099", "路人", "110101199001011899");

        List<Probe> probes = List.of(
                new Probe("/api/settlement/" + settlementId,               "别人的工资单"),
                new Probe("/api/settlement/" + settlementId + "/payslip",  "别人的工资条明细"),
                new Probe("/api/fund/payouts/" + payoutId,                 "别人的代发单(收款人+金额)"),
                new Probe("/api/fund/payouts/" + payoutId + "/disbursement",
                        "别人的代发结果(含完税凭证号)"),
                new Probe("/api/broker/commission/" + commissionId,        "别人的佣金"),
                new Probe("/api/engagement/" + appId,                      "别人的报名单"),
                new Probe("/api/agreement/" + appId,                       "别人的劳务协议正文"),
                new Probe("/api/review/" + appId,                          "别人的评价", true),
                new Probe("/api/org/" + orgId,                             "组织的信用代码与法人"),
                new Probe("/api/fund/accounts/USER_FUNDS",                 "平台监管账户余额")
        );

        List<String> leaks = new ArrayList<>();
        for (Probe p : probes) {
            var r = get(p.path(), outsider.token());
            String body = r.getBody() == null ? "" : r.getBody().trim();
            boolean leaked = p.list()
                    ? !body.equals("[]")                    // 列表:必须空
                    : r.getStatusCode().is2xxSuccessful();  // 单对象:必须查不到
            if (leaked) {
                leaks.add(p.what() + "  ←  " + p.path() + "  返回 " + r.getStatusCode()
                        + "  " + preview(r.getBody()));
            }
        }

        assertThat(leaks)
                .as("""
                    以下接口让一个毫无关系的登录用户看到了别人的数据。
                    这类洞不会有人报错 —— 请求成功、页面正常,只有拿别人的编号去试才发现得了。
                    """)
                .isEmpty();
    }

    private static String preview(String body) {
        if (body == null) return "(空)";
        String one = body.replaceAll("\\s+", " ");
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }

    @Test
    void 正主还看得到自己的东西() {
        // **这条和上面那条同等重要。**挡住路人却把正主一起挡掉,就成了"安全但没用",
        // 而那种错在只测越权的守卫里完全看不见 —— 全绿,功能却废了。
        Token boss   = verified("17900000021", "老板", "110101199001011821");
        Token worker = verified("17900000022", "工人", "110101199001011822");
        Token broker = verified("17900000023", "经纪人", "110101199001011823");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> brokerApi.registerBroker(broker.userId()));
        brokerApi.bindWorker(broker.userId(), worker.userId());

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "正主厂", "9111000000000f03X", boss.userId())));
        long orgId = orgH.get();
        orgApi.approve(orgId, ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgId, "正主岗", "描述", 44_444L, boss.userId())));
        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appH.set(engagementApi.apply(jobH.get(), worker.userId())));
        long appId = appH.get();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, boss.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(appId, worker.userId(), "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(appId, boss.userId()));

        long settlementId = await().atMost(Duration.ofSeconds(25))
                .until(() -> settlements.findByApplicationId(appId).map(s -> s.getId()).orElse(null),
                        java.util.Objects::nonNull);
        fundApi.topUp(AccountType.USER_FUNDS, 5_000_000, "正主备资");
        long payoutId = await().atMost(Duration.ofSeconds(25))
                .until(() -> fundApi.findBySettlementId(settlementId).map(p -> p.id()).orElse(null),
                        java.util.Objects::nonNull);
        fundApi.disburse(payoutId, ops.userId());
        long commissionId = await().atMost(Duration.ofSeconds(25))
                .until(() -> commissions.findAllBySettlementId(settlementId).stream()
                                .filter(c -> c.getBrokerUserId() != null)
                                .map(c -> c.getId()).findFirst().orElse(null),
                        java.util.Objects::nonNull);

        // 平台运维用固定账号,先取一次 userId 确保角色已授予,再拿它的 token
        ops.userId();
        String opsPhone = TestPlatformOps.Accessor.PHONE;
        String opsToken = identityApi.loginByPhone(opsPhone, codes.issue(opsPhone)).token();

        record Case(String path, String token, String who) { }
        List<Case> cases = List.of(
                new Case("/api/fund/payouts/" + payoutId, worker.token(), "工人看自己的代发单"),
                new Case("/api/fund/payouts/" + payoutId + "/disbursement", worker.token(),
                        "工人看自己的代发结果"),
                new Case("/api/agreement/" + appId, worker.token(), "工人看自己签的协议"),
                new Case("/api/engagement/" + appId, worker.token(), "工人看自己的报名单"),
                new Case("/api/engagement/" + appId, boss.token(), "用人单位看收到的报名单"),
                new Case("/api/broker/commission/" + commissionId, broker.token(), "经纪人看自己的佣金"),
                new Case("/api/org/" + orgId, boss.token(), "法人代表看自己的组织"),
                new Case("/api/fund/accounts/USER_FUNDS", opsToken, "平台运维看监管账户余额")
        );

        List<String> broken = new ArrayList<>();
        for (Case c : cases) {
            var r = get(c.path(), c.token());
            if (!r.getStatusCode().is2xxSuccessful()) {
                broken.add(c.who() + "  ←  " + c.path() + "  返回 " + r.getStatusCode());
            }
        }
        assertThat(broken).as("修越权把正主也挡了 —— 这几条功能已经废了").isEmpty();
    }

    @Test
    void 岗位详情是公开的_这条不是洞而是设计() {
        // 反向锚点:招聘信息本来就要能浏览。
        // 没有这条的话,下一个人看到上面那串 404 会顺手把岗位也关掉,
        // 然后"找活"页面就空了 —— 而那种错要等有人抱怨才发现
        Token boss = verified("17900000011", "老板", "110101199001011811");
        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "公开厂", "9111000000000f02X", boss.userId())));
        orgApi.approve(orgH.get(), ops.userId());
        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "公开岗", "描述", 20_000L, boss.userId())));

        Token anyone = verified("17900000012", "路人", "110101199001011812");
        assertThat(get("/api/job/" + jobH.get(), anyone.token()).getStatusCode())
                .as("岗位详情对所有登录用户开放 —— 这是招聘平台的基本功能")
                .isEqualTo(HttpStatus.OK);
    }
}
