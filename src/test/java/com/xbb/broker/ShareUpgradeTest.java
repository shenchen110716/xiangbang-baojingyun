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
    @Autowired com.xbb.broker.internal.ShareConversionRepository conversions;
    @Autowired com.xbb.broker.internal.InvitationRepository invitations;
    /**
     * 直接驱动履约域的中继,不等调度器。
     *
     * <p>报名事件由调度器周期性投递。合跑几百个测试时 outbox 排队变长,
     * 25 秒的等待会失败 —— 而那是**测试基础设施的时序**,不是被测逻辑有问题。
     * 这个代码库里 SettlementOutboxReliabilityTest 早就是这么做的。
     */
    @Autowired com.xbb.engagement.internal.EngagementOutboxRelay engagementRelay;
    @Autowired com.xbb.identity.internal.IdentityOutboxRelay identityRelay;

    /** 报名并把事件推出去。 */
    private long applyAndDeliver(long jobId, long worker) {
        java.util.concurrent.atomic.AtomicLong h = new java.util.concurrent.atomic.AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> h.set(engagementApi.apply(jobId, worker)));
        engagementRelay.publishPending();
        return h.get();
    }

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
        applyAndDeliver(s.jobId, s.newcomer);

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

        long appId = applyAndDeliver(s.jobId, s.newcomer);

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
        applyAndDeliver(s.jobId, s.newcomer);

        // 业务员要被记进佣金归属、要能收钱,没实名的话这些都无从追溯
        assertThat(brokerApi.brokerOrigin(unverified, ops.userId())).isEmpty();
    }

    @Test
    void 实名晚到时不会永久错过升级() {
        threshold(0);
        // **分享人先分享、对方先成交,分享人最后才实名。**
        // 经纪人域的已实名副本是异步来的:副本没到时升级被"未实名"挡下,
        // 而归因已标记为已计数 —— 不补的话,只带来一单的分享人**再也升不上去**。
        //
        // 轻载时副本总是先到,这个缺陷在小范围测试里看不出来;
        // 223 个测试一起跑、outbox 排队变长时才露出来
        long sharer = identityApi.loginByPhone("18400000201", codes.issue("18400000201")).userId();
        Scene s = scene("31", "110101199001070053", "32", "110101199001070054",
                "18400000202", "110101199001070055", "9111000000000k09X");
        brokerApi.attributeShare(brokerApi.share(sharer, RateCategory.JOB, s.jobId), s.newcomer);
        applyAndDeliver(s.jobId, s.newcomer);

        // **先确认归因真的被消耗掉了,再去实名。**
        // 不等的话,报名事件可能在实名之后才投递 —— 那时走的是正常路径,
        // 回补有没有都一样,这条守卫就测不到它要守的东西
        // (第一版就是这样:把回补停掉,它照样绿)
        // **轮询时重新驱动中继。**只在报名后驱动一次是不够的:
        // 后台调度器可能已经抢走这条事件正在处理,那时 publishPending 空转,
        // 而它那次处理若碰上退避就要等下一轮 —— 合跑几百个测试时就会超时
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            engagementRelay.publishPending();
            assertThat(conversions.findByConvertedUserId(s.newcomer))
                    .get().extracting(c -> c.getStatus().name())
                    .isEqualTo("COUNTED");
        });
        assertThat(brokerApi.brokerOrigin(sharer, ops.userId()))
                .as("归因已消耗但人还没实名 —— 此刻升不上去").isEmpty();

        // 现在实名 —— 回补升级
        identityApi.verifyRealName(sharer, "迟到实名", "110101199001070056");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            identityRelay.publishPending();
            assertThat(brokerApi.brokerOrigin(sharer, ops.userId()))
                    .as("实名之后要把此前错过的升级补上").isPresent();
        });
    }

    @Test
    void 没配默认站时自动分配给业务员最少的站() {
        threshold(0);
        // 不归站的业务员在分账时**服务站那一档不会分成**,钱留在池子里 ——
        // 看着没出错,实际是有人该拿的钱没拿到,而且要等对账才发现。
        // 所以没配默认站时自动挑一个,而不是留空
        opsApi.updateSetting(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID, "0", "测试:不配默认站", ops.userId());

        long stationA = orgApi.createStation("自动分配甲站", "9111000000000k10X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == stationA));

        long sharer = verified("18400000211", "分享人", "110101199001070106");
        Scene sc = scene("41", "110101199001070107", "42", "110101199001070108",
                "18400000212", "110101199001070109", "9111000000000k11X");
        brokerApi.attributeShare(brokerApi.share(sharer, RateCategory.JOB, sc.jobId), sc.newcomer);
        applyAndDeliver(sc.jobId, sc.newcomer);

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(sharer, ops.userId())).isPresent());
        assertThat(brokerApi.listBrokers(null, ops.userId()))
                .filteredOn(n -> n.userId() == sharer)
                .singleElement()
                .satisfies(n -> assertThat(n.stationOrgId())
                        .as("没配默认站时要自动分配,不能留空").isNotNull());
    }

    @Test
    void 配了默认站就优先用它() {
        threshold(0);
        long preferred = orgApi.createStation("默认优先站", "9111000000000k12X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == preferred));
        opsApi.updateSetting(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID,
                String.valueOf(preferred), "测试:指定默认站", ops.userId());

        long sharer = verified("18400000213", "分享人", "110101199001070110");
        Scene sc = scene("43", "110101199001070111", "44", "110101199001070112",
                "18400000214", "110101199001070113", "9111000000000k13X");
        brokerApi.attributeShare(brokerApi.share(sharer, RateCategory.JOB, sc.jobId), sc.newcomer);
        applyAndDeliver(sc.jobId, sc.newcomer);

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(sharer, ops.userId())).isPresent());
        assertThat(brokerApi.listBrokers(preferred, ops.userId()))
                .as("配了默认站就该归到它,而不是自动分配")
                .anyMatch(n -> n.userId() == sharer);

        // 收尾:这是全局参数,不还原会影响后面的测试
        opsApi.updateSetting(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID, "0", "测试收尾", ops.userId());
    }

    @Test
    void 自动升级要上树_父节点是把他带进来的业务员() {
        threshold(0);
        // **没有父节点就是根业务员,被动佣金那几档永远不会往上分** ——
        // 而多级裂变的价值恰恰就在这里。第一版只设了服务站没设父节点,
        // 等于把裂变做成了一层
        long station = orgApi.createStation("上树站", "9111000000000k14X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == station));

        // 上级业务员:已在树上,且有服务站
        long upline = verified("18400000221", "上级", "110101199001070163");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                brokerApi.grantBroker(station, upline, ops.userId()));

        // 中间人:被上级带进来,此时还不是业务员
        long middle = verified("18400000222", "中间人", "110101199001070164");
        // 经纪人域的已实名副本是异步来的,不等的话报"工人需要完成实名认证"
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> brokerApi.bindWorker(upline, middle));

        // 中间人分享出去,对方报名 → 中间人升级
        Scene sc = scene("51", "110101199001070165", "52", "110101199001070166",
                "18400000223", "110101199001070167", "9111000000000k15X");
        brokerApi.attributeShare(brokerApi.share(middle, RateCategory.JOB, sc.jobId), sc.newcomer);
        applyAndDeliver(sc.jobId, sc.newcomer);

        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(middle, ops.userId())).isPresent());

        assertThat(brokerApi.listBrokers(station, ops.userId()))
                .filteredOn(n -> n.userId() == middle)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.parentUserId())
                            .as("要挂到把他带进来的业务员下面,否则被动佣金分不上去")
                            .isEqualTo(upline);
                    assertThat(n.stationOrgId())
                            .as("服务站随父节点继承").isEqualTo(station);
                });
    }

    @Test
    void 不经分享直接报名的业务也归默认站长() {
        // **没有归属的业务,佣金那几档全都不分,钱留在池子里** ——
        // 看着没出错,但平台的经营网点对不上账。让它归默认站长
        long station = orgApi.createStation("兜底站", "9111000000000k16X", ops.userId());
        long master = verified("18400000231", "默认站长", "110101199001070217");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                orgApi.assignStationMaster(station, master, "指派", ops.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(x -> x.orgId() == station));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                brokerApi.grantBroker(station, master, ops.userId()));
        opsApi.updateSetting(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID,
                String.valueOf(station), "测试:指定默认站", ops.userId());

        // 一个员工自己直接报名,没有经过任何人的分享
        Scene sc = scene("61", "110101199001070218", "62", "110101199001070219",
                "18400000232", "110101199001070220", "9111000000000k17X");
        applyAndDeliver(sc.jobId, sc.newcomer);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            engagementRelay.publishPending();
            assertThat(invitations.findByWorkerUserId(sc.newcomer))
                    .as("不经分享的单子也要有主,否则佣金那几档全不分")
                    .get().extracting(i -> i.getBrokerUserId()).isEqualTo(master);
        });

        opsApi.updateSetting(SettingKeys.BROKER_DEFAULT_STATION_ORG_ID, "0", "测试收尾", ops.userId());
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
