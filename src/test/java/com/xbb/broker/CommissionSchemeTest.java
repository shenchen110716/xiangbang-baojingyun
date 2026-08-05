package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.RateCategory;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.org.api.OrgApi;
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
 * 按业务类目配**一整套**分配方案(主动/平台/被动/服务站/逐级/下限)。
 *
 * <p>此前只有服务站那一档能按类目设,其余五档全局共用一套 —— 而岗位、商品、培训的
 * 分账结构本来就不同:商品可能没有被动佣金,培训可能主动佣金极高。
 * 用同一套比例去分,任何一个类目都是错的,且**只有对账才看得出来**。
 *
 * <p>号段 187,信用代码 …p xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class CommissionSchemeTest {

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

    @Test
    void 迁移把现状原样搬进来_三个类目都有平台默认() {
        // **上线时行为必须不变。**方案表空着的话,分账会退回参数中心的旧口径
        // 并记一条 error —— 那是兜底,不是正常路径
        var defaults = brokerApi.listSchemes(null, ops.userId());
        assertThat(defaults).extracting(BrokerApi.SchemeView::category)
                .contains(RateCategory.JOB, RateCategory.PRODUCT, RateCategory.TRAINING);

        var job = defaults.stream().filter(x -> x.category().equals(RateCategory.JOB))
                .findFirst().orElseThrow();
        // 和改动前 FundEventListener 里的兜底值一致
        assertThat(job.activePct()).isEqualTo(60);
        assertThat(job.platformPct()).isEqualTo(20);
        assertThat(job.passivePct()).isEqualTo(30);
        assertThat(job.passiveStepPct()).isEqualTo(30);
        assertThat(job.minPayoutCents()).isEqualTo(100);
    }

    @Test
    void 不同类目可以有完全不同的分配结构() {
        // 商品:没有被动佣金,主动给得更高
        brokerApi.setScheme(null, RateCategory.PRODUCT, 75, 15, 0, 85, 0, 100,
                "商品没有裂变,主动给足", ops.userId());
        // 培训:主动极高
        brokerApi.setScheme(null, RateCategory.TRAINING, 90, 50, 25, 25, 20, 50,
                "培训毛利高", ops.userId());

        var all = brokerApi.listSchemes(null, ops.userId());
        var product = all.stream().filter(x -> x.category().equals(RateCategory.PRODUCT))
                .findFirst().orElseThrow();
        var training = all.stream().filter(x -> x.category().equals(RateCategory.TRAINING))
                .findFirst().orElseThrow();

        assertThat(product.passivePct()).as("商品没有被动佣金").isZero();
        assertThat(training.activePct()).isEqualTo(90);
        // **这正是这次改动的要点**:两个类目的整个结构都不同,不只是服务站那一档
        assertThat(product.activePct()).isNotEqualTo(training.activePct());
        assertThat(product.stationPct()).isNotEqualTo(training.stationPct());
    }

    @Test
    void 平台加被动加服务站超过一百被拒() {
        // **这三档在同一块"剩余"里分。**超过 100 就是凭空多分钱,
        // 而分钱那一刻才发现已经晚了:要么少给某一方,要么账不平
        assertThatThrownBy(() -> brokerApi.setScheme(null, RateCategory.JOB, 60, 50, 40, 30, 30, 100,
                "越界", ops.userId()))
                .hasMessageContaining("超过 100");
    }

    @Test
    void 单档比例越界被拒() {
        assertThatThrownBy(() -> brokerApi.setScheme(null, RateCategory.JOB, 101, 20, 30, 50, 30, 100,
                "主动过高", ops.userId()))
                .hasMessageContaining("0 到 100");
        assertThatThrownBy(() -> brokerApi.setScheme(null, RateCategory.JOB, 60, 20, 30, 50, 30, -1,
                "下限为负", ops.userId()))
                .hasMessageContaining("下限不能为负");
    }

    @Test
    void 站点方案覆盖平台默认() {
        long station = orgApi.createStation("方案站", "9111000000000p01X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == station));

        brokerApi.setScheme(null, RateCategory.JOB, 60, 20, 30, 50, 30, 100, "平台默认", ops.userId());
        brokerApi.setScheme(station, RateCategory.JOB, 70, 10, 20, 70, 30, 100,
                "重点站点,主动给足", ops.userId());

        assertThat(brokerApi.listSchemes(station, ops.userId())).singleElement().satisfies(x -> {
            assertThat(x.activePct()).isEqualTo(70);
            assertThat(x.stationPct()).isEqualTo(70);
        });
        // 平台默认不受影响
        assertThat(brokerApi.listSchemes(null, ops.userId()))
                .filteredOn(x -> x.category().equals(RateCategory.JOB))
                .singleElement().extracting(BrokerApi.SchemeView::activePct).isEqualTo(60);
    }

    @Test
    void 要平台运维_站长看得到但改不了() {
        long station = orgApi.createStation("只读方案站", "9111000000000p02X", ops.userId());
        long master = verified("18700000001", "站长", "110101199001070320");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                orgApi.assignStationMaster(station, master, "指派", ops.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == station));
        brokerApi.setScheme(station, RateCategory.JOB, 60, 20, 30, 45, 30, 100, "约定", ops.userId());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listSchemes(station, master)).hasSize(1));
        // **站长能改自己的方案的话,这套数字就没有约束力了**
        assertThatThrownBy(() -> brokerApi.setScheme(station, RateCategory.JOB, 99, 0, 0, 100, 30, 0,
                "自己加", master))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        long outsider = verified("18700000002", "路人", "110101199001070321");
        // 方案是这个站挣多少钱的依据(铁律 5.1)
        assertThat(brokerApi.listSchemes(station, outsider)).isEmpty();
    }

    @Test
    void 变更要填原因() {
        assertThatThrownBy(() -> brokerApi.setScheme(null, RateCategory.JOB, 60, 20, 30, 50, 30, 100,
                " ", ops.userId()))
                .hasMessageContaining("原因");
    }
}
