package com.xbb.broker.internal;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * 服务站那一档佣金要付得出去。
 *
 * <p><b>起因是审计服务站体系时读到的一处类型不匹配:</b>
 * {@code CommissionPaid} 的 brokerUserId 是 {@code long},
 * 而服务站档与联合档的 {@code getBrokerUserId()} 是 {@code null} ——
 * 自动拆箱会 NPE。也就是说**服务站挣的佣金根本付不出去**。
 *
 * <p>这类缺陷编译期看不出来,而且只有真的去付服务站那一档才会碰到 ——
 * 主动佣金(给人的)那条路一直是好的,所以从没暴露过。
 *
* 放在 internal 包下:Commission.toStation 是包内可见的,它不该对外暴露。
 *
 * <p>号段 186,信用代码 …n xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class StationCommissionPayoutTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired BrokerApi brokerApi;
    @Autowired FundApi fundApi;
    @Autowired CommissionRepository commissions;

    @Test
    void 服务站那一档的佣金付得出去() {
        long station = orgApi.createStation("佣金支付站", "9111000000000n01X", ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == station));

        long worker = identityApi.loginByPhone("18600000001", codes.issue("18600000001")).userId();
        // 直接造一条服务站档的佣金 —— 这一档的 brokerUserId 天然是 null
        Commission c = commissions.save(Commission.toStation(station, worker, 999_001L, 5_000));
        fundApi.topUp(AccountType.PLATFORM_REVENUE, 100_000, "备资");

        assertThatCode(() -> brokerApi.payCommission(c.getId(), ops.userId()))
                .as("服务站档的 brokerUserId 是 null,发事件时拆箱会 NPE —— 那样这档佣金永远付不出去")
                .doesNotThrowAnyException();

        assertThat(commissions.findById(c.getId()).orElseThrow().getStatus().name())
                .isEqualTo("PAID");
    }
}
