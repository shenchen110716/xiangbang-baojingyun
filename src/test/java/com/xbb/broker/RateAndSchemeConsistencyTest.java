package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.api.RateCategory;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「平台默认分成比例」和「平台默认分配方案」是不是同一件事。
 *
 * <p>界面上两张卡片都写着"平台默认"、都有"服务站那一档",
 * 但它们写的是**两张不同的表**。这条测试就是要弄清:
 * 运营在旧卡片上改了比例,分账时到底跟不跟着变。
 *
 * <p>这个项目已经栽过一次一模一样的:旧入口写 station_percent、
 * 新入口写 station_rate、读的时候优先新表 —— 运营改了旧的,
 * 界面提示"已更新",**而分账一分钱没变**。
 *
 * <p>号段 13004,信用代码 …w xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class RateAndSchemeConsistencyTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired BrokerApi brokerApi;

    @Test
    void 旧卡片改了比例_分配方案要跟着变() {
        int before = brokerApi.listSchemes(null, ops.userId()).stream()
                .filter(s -> RateCategory.JOB.equals(s.category()))
                .findFirst().orElseThrow().stationPct();

        int target = before == 37 ? 38 : 37;
        brokerApi.setStationRate(null, RateCategory.JOB, target, "一致性验证", ops.userId());

        int after = brokerApi.listSchemes(null, ops.userId()).stream()
                .filter(s -> RateCategory.JOB.equals(s.category()))
                .findFirst().orElseThrow().stationPct();

        // **这一条是全部要害。**不相等的话,运营在界面上改了"平台默认分成比例",
        // 提示"已更新",而真正决定钱怎么分的方案纹丝不动 ——
        // 差额要等对账才发现,而那时钱已经发出去了
        assertThat(after)
                .as("旧的「分成比例」入口必须写到真正生效的方案上,否则界面在骗人")
                .isEqualTo(target);
    }

    @Test
    void 快捷改超出剩余时拦下_并且报的话要能照着做() {
        // 平台 20 + 被动 30 已占 50,服务站再要 55 就是 105% —— 凭空多分 5%。
        // 旧的「服务站比例」单独存一张表时随便填都行,写进去也没人读;
        // 现在它同步到真正生效的方案上,必须拦。
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                brokerApi.setStationRate(null, RateCategory.JOB, 95, "超额", ops.userId()))
                // **只说"超过 100"是不够的** —— 运营在界面上只填了一个数字,
                // 看到那句话不知道该改什么。得告诉他另一条路
                .hasMessageContaining("整套方案");
    }
}
