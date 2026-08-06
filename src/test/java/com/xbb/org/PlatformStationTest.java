package com.xbb.org;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.RateCategory;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 服务站由平台统一设立:先建实体,再关联站长(可改)。
 *
 * <p>改动前服务站和工厂一样走"用户提交 → 平台审核",提交人自动成为负责人且此后不可更改。
 * 那个顺序是反的 —— 平台要先规划好点位,再决定派谁去管;而且换站长时没有任何办法。
 *
 * <p>号段 183,信用代码 …j xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class PlatformStationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired BrokerApi brokerApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    /**
     * 指派站长,并等实名副本落地。
     *
     * <p>组织域的已实名用户副本是**异步**从身份域来的。不等的话,
     * 刚实名的人立刻指派会得到"新站长未实名认证" —— 报错和真正的未实名一模一样,
     * 看不出是副本还没到。
     */
    private void assignWhenReady(long orgId, Long master, String reason) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                orgApi.assignStationMaster(orgId, master, reason, ops.userId()));
    }

    private long approvedFactory(long boss, String name, String creditCode) {
        java.util.concurrent.atomic.AtomicLong h = new java.util.concurrent.atomic.AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.FACTORY, name, creditCode, boss)));
        orgApi.approve(h.get(), ops.userId());
        return h.get();
    }

    // ─────────────── 建站 ───────────────

    @Test
    void 平台建站时还没有站长_但已经是已审核() {
        long orgId = orgApi.createStation("郑州高新区服务站", "9111000000000j01X", ops.userId());

        var view = orgApi.findById(orgId, ops.userId()).orElseThrow();
        assertThat(view.type()).isEqualTo(OrgType.SERVICE_STATION);
        // **建出来就是已审核** —— 平台自己设的点位,没有"谁来审"这一说
        assertThat(view.status().name()).isEqualTo("APPROVED");
        // **还没有站长。**这正是这次改动的要点:先有点位,再派人
        assertThat(view.legalRepUserId()).isNull();
    }

    @Test
    void 无站长的服务站不会让审核路径炸掉() {
        // legalRepUserId 从 long 改成 Long 之后,任何 `== callerUserId` 都会拆箱。
        // **那是编译期看不出来、运行时才 NPE 的**,所以专门钉一条:
        // 建一个无站长的站,然后走一遍会读到它的路径
        long orgId = orgApi.createStation("空站长站", "9111000000000j02X", ops.userId());
        long outsider = verified("18300000001", "路人", "110101199001050001");

        // 路人查:不能 NPE,要正常返回空
        assertThat(orgApi.findById(orgId, outsider)).isEmpty();
        // 平台查:正常返回
        assertThat(orgApi.findById(orgId, ops.userId())).isPresent();
    }

    @Test
    void 不是平台运维不能建站() {
        long outsider = verified("18300000002", "路人", "110101199001050002");
        assertThatThrownBy(() -> orgApi.createStation("野站", "9111000000000j03X", outsider))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void 建站要填名称与信用代码() {
        assertThatThrownBy(() -> orgApi.createStation("  ", "9111000000000j04X", ops.userId()))
                .hasMessageContaining("名称");
        // 服务站是要收佣金的经营主体,没有信用代码开不了对公账户、走不了代发
        assertThatThrownBy(() -> orgApi.createStation("无码站", "  ", ops.userId()))
                .hasMessageContaining("信用代码");
    }

    // ─────────────── 指派与更换站长 ───────────────

    @Test
    void 指派站长后可以再换人_并且留痕() {
        long orgId = orgApi.createStation("换人站", "9111000000000j05X", ops.userId());
        long first = verified("18300000003", "老站长", "110101199001050003");
        long second = verified("18300000004", "新站长", "110101199001050004");

        assignWhenReady(orgId, first, "首次指派");
        assertThat(orgApi.findById(orgId, ops.userId()).orElseThrow().legalRepUserId()).isEqualTo(first);

        assignWhenReady(orgId, second, "原站长调岗");
        assertThat(orgApi.findById(orgId, ops.userId()).orElseThrow().legalRepUserId()).isEqualTo(second);

        // **留痕**:换站长会改变谁能设分成比例、谁能签联合协议,那都是动钱的权力
        var changes = orgApi.stationMasterChanges(orgId, ops.userId());
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).oldUserId()).isEqualTo(first);
        assertThat(changes.get(0).newUserId()).isEqualTo(second);
        assertThat(changes.get(0).reason()).isEqualTo("原站长调岗");
    }

    @Test
    void 可以撤下站长让站暂时无人管理() {
        long orgId = orgApi.createStation("撤人站", "9111000000000j06X", ops.userId());
        long master = verified("18300000005", "站长", "110101199001050005");
        assignWhenReady(orgId, master, "指派");

        assignWhenReady(orgId, null, "站长离职,暂无接替");
        assertThat(orgApi.findById(orgId, ops.userId()).orElseThrow().legalRepUserId()).isNull();
    }

    @Test
    void 换成同一个人被拒绝() {
        long orgId = orgApi.createStation("同人站", "9111000000000j07X", ops.userId());
        long master = verified("18300000006", "站长", "110101199001050006");
        assignWhenReady(orgId, master, "指派");
        // 不是变更,是误操作。让它落库只会在审计时制造噪音
        assertThatThrownBy(() -> orgApi.assignStationMaster(orgId, master, "再来一次", ops.userId()))
                .hasMessageContaining("同一个人");
    }

    @Test
    void 换站长要填原因() {
        long orgId = orgApi.createStation("原因站", "9111000000000j08X", ops.userId());
        long master = verified("18300000007", "站长", "110101199001050007");
        assertThatThrownBy(() -> orgApi.assignStationMaster(orgId, master, " ", ops.userId()))
                .hasMessageContaining("原因");
    }

    @Test
    void 未实名的人不能当站长() {
        long orgId = orgApi.createStation("实名站", "9111000000000j09X", ops.userId());
        long unverified = identityApi.loginByPhone("18300000008", codes.issue("18300000008")).userId();
        // 站长要签联合协议、要被记进佣金归属,没实名的话这些都无从追溯
        assertThatThrownBy(() -> orgApi.assignStationMaster(orgId, unverified, "指派", ops.userId()))
                .hasMessageContaining("实名");
    }

    @Test
    void 不是平台运维不能换站长() {
        long orgId = orgApi.createStation("越权换人站", "9111000000000j10X", ops.userId());
        long outsider = verified("18300000009", "路人", "110101199001050009");
        assertThatThrownBy(() -> orgApi.assignStationMaster(orgId, outsider, "自己上位", outsider))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void 工厂不能用换站长这条路改法人() {
        long boss = verified("18300000010", "老板", "110101199001050010");
        long factory = approvedFactory(boss, "某工厂", "9111000000000j11X");
        long other = verified("18300000011", "别人", "110101199001050011");

        // 工厂的法人代表不是平台能改的 —— 那是工商登记信息
        assertThatThrownBy(() -> orgApi.assignStationMaster(factory, other, "改法人", ops.userId()))
                .hasMessageContaining("只有服务站");
    }

    @Test
    void 指派站长后他自动成为本站业务员() {
        long orgId = orgApi.createStation("自动业务员站", "9111000000000j20X", ops.userId());
        long master = verified("18300000031", "站长", "110101199001070270");
        assignWhenReady(orgId, master, "指派");

        // 站长在佣金树顶端。不是业务员的话,「无归属业务归默认站长」那条规则
        // 会**静默跳过** —— 规则写了却不起作用,比没写更糟
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(brokerApi.brokerOrigin(master, ops.userId()))
                        .as("站长要自动登记为本站业务员").isPresent());
        assertThat(brokerApi.listBrokers(orgId, ops.userId()))
                .anyMatch(n -> n.userId() == master);
    }

    // ─────────────── 按类目的分成比例 ───────────────

    @Test
    void 分成比例按类目分别设_三级取数() {
        long orgId = orgApi.createStation("费率站", "9111000000000j12X", ops.userId());
        long master = verified("18300000012", "站长", "110101199001050012");
        assignWhenReady(orgId, master, "指派");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));

        // 平台默认:岗位 40、商品 45
        //
        // **原来这里写的是 55 和 60,那是兑现不了的值。**平台 20 + 被动 30 已占 50,
        // 服务站再拿 55 就是 105% —— 旧的「服务站比例」单独存一张表时没人拦,
        // 写进去也没人读;现在它同步到真正生效的方案上,超了会被拦下。
        // 要给服务站更高的份额,得用整套方案把平台/被动一起调低。
        brokerApi.setStationRate(null, RateCategory.JOB, 40, "平台默认", ops.userId());
        brokerApi.setStationRate(null, RateCategory.PRODUCT, 45, "商品毛利更高", ops.userId());
        // 这个站在岗位上单独谈到 50
        brokerApi.setStationRate(orgId, RateCategory.JOB, 50, "重点站点", ops.userId());

        var stationRates = brokerApi.listStationRates(orgId, ops.userId());
        assertThat(stationRates).singleElement()
                .satisfies(r -> {
                    assertThat(r.category()).isEqualTo(RateCategory.JOB);
                    assertThat(r.percent()).isEqualTo(50);
                });

        var defaults = brokerApi.listStationRates(null, ops.userId());
        assertThat(defaults).hasSize(2);
        // **岗位和商品的毛利结构不同,用同一个比例要么服务站在商品上亏、要么平台在岗位上亏**
        assertThat(defaults).extracting(BrokerApi.StationRateView::percent)
                .containsExactlyInAnyOrder(40, 45);
    }

    @Test
    void 站长看得到自己站的比例_但改不了() {
        long orgId = orgApi.createStation("只读站", "9111000000000j13X", ops.userId());
        long master = verified("18300000013", "站长", "110101199001050013");
        assignWhenReady(orgId, master, "指派");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));
        brokerApi.setStationRate(orgId, RateCategory.JOB, 45, "约定", ops.userId());

        // 站长变更经 outbox 异步同步到经纪人域的副本,而授权判断读的正是那个副本
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStationRates(orgId, master)).hasSize(1));
        // **站长能改自己的比例的话,这个数字就没有约束力了**
        assertThatThrownBy(() -> brokerApi.setStationRate(orgId, RateCategory.JOB, 99, "自己加", master))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void 路人看不到分成比例() {
        long orgId = orgApi.createStation("保密站", "9111000000000j14X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));
        brokerApi.setStationRate(orgId, RateCategory.JOB, 45, "约定", ops.userId());
        long outsider = verified("18300000014", "路人", "110101199001050014");
        // 比例是这个站挣多少钱的依据(铁律 5.1)
        assertThat(brokerApi.listStationRates(orgId, outsider)).isEmpty();
    }

    @Test
    void 比例越界与缺原因被拒() {
        long orgId = orgApi.createStation("越界站", "9111000000000j15X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));
        assertThatThrownBy(() -> brokerApi.setStationRate(orgId, RateCategory.JOB, 101, "过高", ops.userId()))
                .hasMessageContaining("0 到 100");
        assertThatThrownBy(() -> brokerApi.setStationRate(orgId, RateCategory.JOB, -1, "负数", ops.userId()))
                .hasMessageContaining("0 到 100");
        assertThatThrownBy(() -> brokerApi.setStationRate(orgId, RateCategory.JOB, 50, " ", ops.userId()))
                .hasMessageContaining("原因");
    }
}
