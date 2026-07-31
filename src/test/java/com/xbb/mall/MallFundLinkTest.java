package com.xbb.mall;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.mall.api.MallApi;
import com.xbb.mall.api.ProductSettlementMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商城的钱必须真的进账本。
 *
 * <p>此前商城**完全没有引用 FundApi**:订单支付后只发一个事件给报表域,
 * 报表上出现收入,而监管账户里一分钱都没有。账实不符,而且是静默的——
 * 对账时才会发现"报表说赚了,账上没有"。
 *
 * <p>§4.1 决策#1:资金域是唯一动钱者。商城要收钱就得调 FundApi,
 * 不能只发个事件了事。
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class MallFundLinkTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MallApi mallApi;
    @Autowired FundApi fundApi;

    @Test
    void 商城支付要让平台收入账户真的增加() {
        long merchantId = 7701L;
        long productId = mallApi.publishProduct(merchantId, "工牌", ProductSettlementMode.INSTANT,
                12_000, "一张", 10, null);
        long orderId = mallApi.placeOrder(productId, 7702L);

        long before = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);
        mallApi.pay(orderId);
        long after = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);

        assertThat(after - before)
                .as("支付 120 元后,平台收入账户应真的多出 120 元;"
                        + "只发报表事件而不动账本 = 报表有收入、账上没钱")
                .isEqualTo(12_000);
    }

    @Test
    void 重复支付同一订单不会重复入账() {
        long merchantId = 7703L;
        long productId = mallApi.publishProduct(merchantId, "手套", ProductSettlementMode.INSTANT,
                3_000, "一副", 10, null);
        long orderId = mallApi.placeOrder(productId, 7704L);

        mallApi.pay(orderId);
        long afterFirst = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);

        // 再付一次:订单状态机会拒绝,账本更不该动。
        // 就算将来状态机放松了,入账的幂等键是订单号,也兜得住。
        try {
            mallApi.pay(orderId);
        } catch (RuntimeException expected) {
            // 已支付的订单不能再付,正常
        }

        assertThat(fundApi.balanceOf(AccountType.PLATFORM_REVENUE))
                .as("重复支付不该让平台收入翻倍")
                .isEqualTo(afterFirst);
    }
}
