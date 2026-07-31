package com.xbb.reporting;

import com.xbb.TestcontainersConfig;
import com.xbb.reporting.api.ReportingApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * 平台的直接成本必须真的被记账。
 *
 * <p>`DIRECT_COST` 此前只有枚举定义,**一次都没被写入**——
 * `profitAndLoss` 里 `row[1]` 永远是 0。后果是平台盈亏被**系统性高估**:
 * 发出去的工资和佣金是平台的真金白银支出,却完全不进成本。
 *
 * <p>注意这跟"对工人是收入"不矛盾:同一笔钱在**不同维度**上是两条事实——
 * 工人维度记 REVENUE(他挣到了),平台维度记 DIRECT_COST(平台付出去了)。
 * 缺了后者,报表只算了一半。
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class DirectCostTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    /** 平台自身在报表里的维度 id。平台不是某个 ORG,用固定负数 id 与真实组织区分开。 */
    private static final long PLATFORM_ID = -1L;

    @Autowired ReportingApi reportingApi;
    @Autowired ApplicationEventPublisher events;

    private long platformDirectCost() {
        // 报表是累加的,别的用例也会往这个维度写。所以断言**增量**,不断言绝对值——
        // 断绝对值的话谁先跑谁通过,是 flaky 的经典成因。
        return reportingApi.profitAndLoss(ReportingApi.Dimension.ORG, Map.of())
                .stream().filter(p -> p.dimensionId() == PLATFORM_ID)
                .findFirst().map(ReportingApi.ProfitLoss::directCostCents).orElse(0L);
    }

    @Test
    void 代发工资在平台维度记为直接成本() {
        long before = platformDirectCost();
        events.publishEvent(new com.xbb.fund.api.FundsDisbursed(
                91_001L, 91_101L, 91_201L, 50_000, Instant.now()));

        await().atMost(ofSeconds(15)).untilAsserted(() ->
                assertThat(platformDirectCost() - before)
                        .as("发出去的工资是平台的直接成本,不记就等于盈亏被高估")
                        .isEqualTo(50_000));
    }

    @Test
    void 同一笔代发在工人维度仍是收入() {
        long payoutId = 91_002L;
        long worker = 91_202L;
        events.publishEvent(new com.xbb.fund.api.FundsDisbursed(
                payoutId, 91_102L, worker, 30_000, Instant.now()));

        await().atMost(ofSeconds(15)).untilAsserted(() ->
                assertThat(reportingApi.profitAndLoss(ReportingApi.Dimension.WORKER, Map.of())
                        .stream().filter(p -> p.dimensionId() == worker).findFirst())
                        .as("同一笔钱在工人维度是收入,两条事实并存")
                        .isPresent());
    }
}
