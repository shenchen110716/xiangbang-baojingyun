package com.xbb.broker.internal;

import com.xbb.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 佣金比例的地区回退,**在真数据库上**。
 *
 * <p>纯函数那条(RegionScope)已经单独验过候选顺序;这里验的是
 * "按那个顺序去查表,先命中的赢",以及**没配时报的是不是人话**。
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class CommissionRateResolverTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired CommissionRateResolver resolver;
    @Autowired CommissionRateRepository rates;

    /** 类目用测试专属的,免得和别的测试抢同一行。 */
    private static final String CAT = "TEST_REGION_FALLBACK";

    @Test
    @Transactional("brokerTransactionManager")
    void 区县配了就用区县的_没配才往上找() {
        rates.save(new CommissionRate(CAT, null, 8, 0, null, 1L));           // 全国 8%
        rates.save(new CommissionRate(CAT, "320000", 10, 0, null, 1L));      // 江苏 10%
        rates.save(new CommissionRate(CAT, "320500", 12, 0, null, 1L));      // 苏州 12%
        rates.save(new CommissionRate(CAT, "320506", 15, 0, null, 1L));      // 吴中区 15%

        assertThat(resolver.resolve(CAT, "320506").getCommissionPct()).isEqualTo(15);
        // 320507 没单独配 → 回退到苏州
        assertThat(resolver.resolve(CAT, "320507").getCommissionPct()).isEqualTo(12);
        // 320100 没配、南京也没配 → 回退到江苏
        assertThat(resolver.resolve(CAT, "320100").getCommissionPct()).isEqualTo(10);
        // 广东完全没配 → 全国
        assertThat(resolver.resolve(CAT, "440300").getCommissionPct()).isEqualTo(8);
        assertThat(resolver.resolve(CAT, null).getCommissionPct()).isEqualTo(8);
    }

    @Test
    @Transactional("brokerTransactionManager")
    void 一条都没配时报错而不是给个默认值() {
        // **这一条是故意的。**给个"就按 10% 吧"的兜底,总价岗位一上线
        // 就会按那个编出来的数字扣钱,而没有任何人知道
        assertThatThrownBy(() -> resolver.resolve("TEST_NEVER_CONFIGURED", "320506"))
                .hasMessageContaining("没有配佣金比例")
                .hasMessageContaining("全国兜底");
    }

    @Test
    @Transactional("brokerTransactionManager")
    void 留了派遣比例却没指定收款方_当场拒绝() {
        // 那笔钱从佣金池里扣掉、挂不到任何收款方 —— 对账时是个凭空消失的窟窿
        assertThatThrownBy(() -> new CommissionRate(CAT, "330100", 10, 30, null, 1L))
                .hasMessageContaining("必须指定收款的派遣公司");
    }
}
