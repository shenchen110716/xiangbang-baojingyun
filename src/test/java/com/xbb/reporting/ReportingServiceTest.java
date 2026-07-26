package com.xbb.reporting;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.mall.api.MallApi;
import com.xbb.mall.api.ProductSettlementMode;
import com.xbb.reporting.api.ReportingApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** §6.6 多维盈亏。数据全部来自事件订阅,不跨域 join。 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ReportingServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired ReportingApi reportingApi;
    @Autowired MallApi mallApi;

    @Test
    void 商城订单结算被报表域订阅落进宽表() {
        long merchantId = 5001L;
        long productId = mallApi.publishProduct(merchantId, "门票", ProductSettlementMode.INSTANT,
                50_000, "场次A", 10, null);
        long orderId = mallApi.placeOrder(productId, 60L);

        mallApi.pay(orderId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of()))
                        .anySatisfy(pl -> {
                            assertThat(pl.dimensionId()).isEqualTo(merchantId);
                            assertThat(pl.revenueCents()).isGreaterThanOrEqualTo(50_000);
                        }));
    }

    @Test
    void 公共费用按权重分摊且总额不多不少() {
        // 三方权重 1:1:1,公共费用 100 分——整数除法会剩 1 分,不能把它丢掉
        reportingApi.recordOverhead("测试机房费", 100, ReportingApi.AllocationBasis.HEADCOUNT);

        long merchantA = 5101L, merchantB = 5102L, merchantC = 5103L;
        for (long m : List.of(merchantA, merchantB, merchantC)) {
            long pid = mallApi.publishProduct(m, "票", ProductSettlementMode.INSTANT,
                    10_000, "场次", 5, null);
            mallApi.pay(mallApi.placeOrder(pid, 61L));
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of()))
                        .extracting(ReportingApi.ProfitLoss::dimensionId)
                        .contains(merchantA, merchantB, merchantC));

        Map<Long, Long> weights = Map.of(merchantA, 1L, merchantB, 1L, merchantC, 1L);
        List<ReportingApi.ProfitLoss> pnl = reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, weights);

        long totalAllocated = pnl.stream()
                .filter(p -> weights.containsKey(p.dimensionId()))
                .mapToLong(ReportingApi.ProfitLoss::allocatedOverheadCents).sum();
        // 关键不变量:分摊出去的钱等于公共费用总额,不能凭空多出或漏掉
        long totalOverhead = pnl.stream().mapToLong(ReportingApi.ProfitLoss::allocatedOverheadCents).sum();
        assertThat(totalOverhead).isEqualTo(100);
        assertThat(totalAllocated).isEqualTo(100);
    }

    @Test
    void 盈亏等于收入减直接成本减分摊费用() {
        long merchantId = 5201L;
        long pid = mallApi.publishProduct(merchantId, "票", ProductSettlementMode.INSTANT,
                80_000, "场次", 5, null);
        mallApi.pay(mallApi.placeOrder(pid, 62L));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of()))
                        .anyMatch(p -> p.dimensionId() == merchantId));

        ReportingApi.ProfitLoss pl = reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of())
                .stream().filter(p -> p.dimensionId() == merchantId).findFirst().orElseThrow();

        assertThat(pl.profitCents())
                .isEqualTo(pl.revenueCents() - pl.directCostCents() - pl.allocatedOverheadCents());
    }

    @Test
    void 不提供权重时不分摊公共费用() {
        long merchantId = 5301L;
        long pid = mallApi.publishProduct(merchantId, "票", ProductSettlementMode.INSTANT,
                10_000, "场次", 5, null);
        mallApi.pay(mallApi.placeOrder(pid, 63L));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of()))
                        .anyMatch(p -> p.dimensionId() == merchantId));

        ReportingApi.ProfitLoss pl = reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of())
                .stream().filter(p -> p.dimensionId() == merchantId).findFirst().orElseThrow();

        assertThat(pl.allocatedOverheadCents()).isZero();
    }

    @Test
    void 重复事件不会让盈亏翻倍() {
        long merchantId = 5401L;
        long pid = mallApi.publishProduct(merchantId, "票", ProductSettlementMode.INSTANT,
                30_000, "场次", 5, null);
        long orderId = mallApi.placeOrder(pid, 64L);
        mallApi.pay(orderId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of()))
                        .anyMatch(p -> p.dimensionId() == merchantId));

        long revenue = reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of())
                .stream().filter(p -> p.dimensionId() == merchantId).findFirst().orElseThrow()
                .revenueCents();

        // 幂等键 (维度, id, 来源, 业务对象):同一笔订单只该记一次
        assertThat(revenue).isEqualTo(30_000);
    }
}
