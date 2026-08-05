package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.agreement.api.AgreementApi;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.RateCategory;
import com.xbb.engagement.api.EngagementApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 业务员的两条产生路径:分享带来成交后自动升级、站长授权。
 *
 * <p>改动前只有自助注册一条路,普通员工没有任何变成业务员的途径 ——
 * 而"分享给别人、别人成交了、于是你成了业务员"正是老系统 M10 裂变模型的入口。
 *
 * <p>守的要害是**归属唯一**和**幂等**:前者错了佣金会重复计算,
 * 后者错了一单能顶好几单。
 *
 * <p>号段 184,信用代码 …k xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class ShareUpgradeTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;
    @Autowired EngagementApi engagementApi;
    @Autowired AgreementApi agreementApi;
    @Autowired BrokerApi brokerApi;
    @Autowired OpsApi opsApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    private void threshold(int n) {
        opsApi.updateSetting(SettingKeys.BROKER_UPGRADE_DEAL_THRESHOLD,
                String.valueOf(n), "测试设置门槛", ops.userId());
    }

    // ─────────────── 分享与归因 ───────────────

    @Test
    void 同一个人重复分享同一个岗位返回同一个码() {
        long sharer = verified("18400000001", "员工", "110101199001060001");
        String a = brokerApi.share(sharer, RateCategory.JOB, 777L);
        String b = brokerApi.share(sharer, RateCategory.JOB, 777L);
        // 每次新建的话,同一个人会有一堆等价的分享码,
        // 归因统计时每条各算各的,谁也说不清他到底带来了几单
        assertThat(a).isEqualTo(b);
    }

    @Test
    void 归属唯一_已归因的人不会被别人抢走() {
        long first = verified("18400000002", "员工甲", "110101199001060002");
        long second = verified("18400000003", "员工乙", "110101199001060003");
        long newcomer = verified("18400000004", "新人", "110101199001060004");

        assertThat(brokerApi.attributeShare(brokerApi.share(first, RateCategory.JOB, 1L), newcomer)).isTrue();
        // **允许改的话,两个人分享给同一个人时两边都算业绩,佣金会被重复计算**
        assertThat(brokerApi.attributeShare(brokerApi.share(second, RateCategory.JOB, 1L), newcomer)).isFalse();
    }

    @Test
    void 自己点自己的分享链接不算数() {
        long sharer = verified("18400000005", "员工", "110101199001060005");
        // 放过去的话,分享一下再自己报名就能把自己升成业务员
        assertThat(brokerApi.attributeShare(brokerApi.share(sharer, RateCategory.JOB, 1L), sharer)).isFalse();
    }

    @Test
    void 不存在的分享码被忽略而不是报错() {
        long newcomer = verified("18400000006", "新人", "110101199001060006");
        // 分享码是从外部带进来的(链接里),乱填一个不该让报名流程崩掉
        assertThat(brokerApi.attributeShare("不存在的码", newcomer)).isFalse();
    }

    // ─────────────── 自动升级 ───────────────

    @Test
    void 门槛为零时对方报名即升级() {
        threshold(0);
        Scene s = scene("07", "110101199001060007", "08", "110101199001060008",
                "18400000101", "110101199001060120", "9111000000000k01X");
        String code = brokerApi.share(s.sharer, RateCategory.JOB, s.jobId);
        brokerApi.attributeShare(code, s.newcomer);

        // 只报名,不完成履约
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> engagementApi.apply(s.jobId, s.newcomer));

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(s.sharer, ops.userId()))
                        .as("门槛 0 = 对方报名即升级,不等成交")
                        .isPresent()
                        .get().extracting(BrokerApi.BrokerOriginView::origin).isEqualTo("AUTO_UPGRADE"));
    }

    @Test
    void 门槛为一时报名不升级_成交才升级() {
        threshold(1);
        Scene s = scene("09", "110101199001060009", "10", "110101199001060010",
                "18400000102", "110101199001060121", "9111000000000k02X");
        String code = brokerApi.share(s.sharer, RateCategory.JOB, s.jobId);
        brokerApi.attributeShare(code, s.newcomer);

        AtomicLong appH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                appH.set(engagementApi.apply(s.jobId, s.newcomer)));
        long appId = appH.get();

        // **报名还不算**。门槛设成 1 却在报名时就升级,等于把"成交"偷换成"报名"
        assertThat(brokerApi.brokerOrigin(s.sharer, ops.userId())).isEmpty();

        // 走完履约 → 结算
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                engagementApi.acceptApplication(appId, s.boss));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                agreementApi.sign(appId, s.newcomer, "SMS"));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                engagementApi.completeApplication(appId, s.boss));

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(s.sharer, ops.userId())).isPresent());
    }

    @Test
    void 未实名的分享人不会被升级() {
        threshold(0);
        long unverified = identityApi.loginByPhone("18400000011", codes.issue("18400000011")).userId();
        Scene s = scene("12", "110101199001060012", "13", "110101199001060013",
                "18400000103", "110101199001060122", "9111000000000k03X");
        brokerApi.attributeShare(brokerApi.share(unverified, RateCategory.JOB, s.jobId), s.newcomer);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> engagementApi.apply(s.jobId, s.newcomer));

        // 业务员要被记进佣金归属、要能收钱,没实名的话这些都无从追溯
        assertThat(brokerApi.brokerOrigin(unverified, ops.userId())).isEmpty();
    }

    // ─────────────── 站长授权 ───────────────

    @Test
    void 站长可以直接授权业务员并挂在本站下() {
        long stationOrg = orgApi.createStation("授权站", "9111000000000k04X", ops.userId());
        long master = verified("18400000014", "站长", "110101199001060014");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                orgApi.assignStationMaster(stationOrg, master, "指派", ops.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == stationOrg));

        long staff = verified("18400000015", "员工", "110101199001060015");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                brokerApi.grantBroker(stationOrg, staff, master));

        var origin = brokerApi.brokerOrigin(staff, ops.userId()).orElseThrow();
        assertThat(origin.origin()).isEqualTo("STATION_GRANT");
        assertThat(origin.grantedBy()).isEqualTo(master);
        assertThat(brokerApi.listBrokers(stationOrg, ops.userId()))
                .anyMatch(n -> n.userId() == staff && java.util.Objects.equals(n.stationOrgId(), stationOrg));
    }

    @Test
    void 不是站长不能授权业务员() {
        long stationOrg = orgApi.createStation("越权授权站", "9111000000000k05X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == stationOrg));
        long outsider = verified("18400000016", "路人", "110101199001060016");
        long staff = verified("18400000017", "员工", "110101199001060017");

        // 这是在往别人的站里加一个能分佣金的人
        assertThatThrownBy(() -> brokerApi.grantBroker(stationOrg, staff, outsider))
                .hasMessageContaining("站长");
    }

    @Test
    void 未实名的人不能被授权为业务员() {
        long stationOrg = orgApi.createStation("实名授权站", "9111000000000k06X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == stationOrg));
        long unverified = identityApi.loginByPhone("18400000018", codes.issue("18400000018")).userId();

        assertThatThrownBy(() -> brokerApi.grantBroker(stationOrg, unverified, ops.userId()))
                .hasMessageContaining("实名");
    }

    @Test
    void 业务员来源不对外泄露() {
        long staff = verified("18400000019", "员工", "110101199001060019");
        long outsider = verified("18400000020", "路人", "110101199001060020");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> brokerApi.registerBroker(staff));

        // 别人凭什么是业务员,不该给无关的人看(铁律 5.1)
        assertThat(brokerApi.brokerOrigin(staff, outsider)).isEmpty();
        // 本人和平台运维看得到 —— 挡住路人不能连正主一起挡掉
        assertThat(brokerApi.brokerOrigin(staff, staff)).isPresent();
        assertThat(brokerApi.brokerOrigin(staff, ops.userId())).isPresent()
                .get().extracting(BrokerApi.BrokerOriginView::origin).isEqualTo("SELF");
    }

    // ─────────────── 脚手架 ───────────────

    private record Scene(long boss, long sharer, long newcomer, long jobId) { }

    /**
     * 造一个"老板发了岗位"的场景。
     *
     * <p>老板的身份证**显式传进来**,不要用分享人的号去截取拼接 ——
     * 我第一版就是那么写的,拼出来的号正好撞上另一条测试的分享人,
     * 报错是含糊的"该身份证已被绑定",完全看不出是拼号拼出来的。
     */
    private Scene scene(String sharerSuffix, String sharerId, String newcomerSuffix, String newcomerId,
                        String bossPhone, String bossId, String creditCode) {
        long boss = verified(bossPhone, "老板", bossId);
        long sharer = verified("184000000" + sharerSuffix, "分享人", sharerId);
        long newcomer = verified("184000000" + newcomerSuffix, "新人", newcomerId);

        AtomicLong orgH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                orgH.set(orgApi.submit(OrgType.FACTORY, "分享厂" + creditCode, creditCode, boss)));
        orgApi.approve(orgH.get(), ops.userId());

        AtomicLong jobH = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                jobH.set(jobApi.postJob(orgH.get(), "分享岗", "描述", 30_000L, boss)));
        return new Scene(boss, sharer, newcomer, jobH.get());
    }
}
