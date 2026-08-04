package com.xbb.ops;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台参数中心的守卫。
 *
 * <p>最要紧的是第一条:**代码读了某个键,而迁移里没种**。
 * 这种错不会报错、不会崩,只会静默退回代码里的兜底值 ——
 * 于是运营在界面上把佣金从 60% 改成 40%,系统还在按 60% 算,账面上完全看不出异常。
 * 只能靠机器在 CI 阶段挡。
 *
 * <p>号段 169。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class PlatformSettingTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired OpsApi opsApi;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;

    private String opsToken() {
        ops.userId();
        return identityApi.loginByPhone(TestPlatformOps.Accessor.PHONE,
                codes.issue(TestPlatformOps.Accessor.PHONE)).token();
    }

    private String plainToken(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).token();
    }

    @Test
    void 代码引用的每个键都必须在数据库里有对应行() {
        Set<String> seeded = opsApi.allSettings().stream()
                .map(OpsApi.SettingView::key).collect(java.util.stream.Collectors.toSet());
        assertThat(seeded)
                .as("有键被 SettingKeys 声明却没被迁移种下 —— 读它只会静默拿兜底值")
                .containsAll(SettingKeys.ALL);
    }

    @Test
    void 种子值与改动前的硬编码常量一致() {
        // 这一版**不该改变任何现有行为**,只是把值搬到可改的地方。
        // 行为变更应该是运营在界面上做的决定,不是发版时悄悄带的。
        assertThat(opsApi.settingDecimal(SettingKeys.CREDIT_NEW_USER_SCORE, BigDecimal.ZERO))
                .isEqualByComparingTo("60");
        assertThat(opsApi.settingInt(SettingKeys.WAGE_MIN_CENTS, -1)).isEqualTo(5_000);
        assertThat(opsApi.settingInt(SettingKeys.WAGE_MAX_CENTS, -1)).isEqualTo(500_000);
        assertThat(opsApi.settingDecimal(SettingKeys.DEPOSIT_FULL_RATE, BigDecimal.ZERO))
                .isEqualByComparingTo("0.5");
        assertThat(opsApi.settingDecimal(SettingKeys.MATCHING_EPSILON, BigDecimal.ZERO))
                .isEqualByComparingTo("0.2");
        assertThat(opsApi.settingDecimal(SettingKeys.VOICE_MIN_CONFIDENCE, BigDecimal.ZERO))
                .isEqualByComparingTo("0.7");
    }

    @Test
    void 键不存在时退回兜底值而不是抛异常() {
        // 参数缺失让整个应用起不来,代价比"用回原来的常量"大得多。
        // 风险由上面那条覆盖测试在 CI 阶段消掉,不留到运行期。
        assertThat(opsApi.settingInt("no.such.key.at.all", 42)).isEqualTo(42);
    }

    @Test
    void 改参数必须填理由() {
        assertThatThrownBy(() -> opsApi.updateSetting(
                SettingKeys.BROKER_DEMOTION_DAYS, "60", "  ", ops.userId()))
                .hasMessageContaining("理由");
    }

    @Test
    void 改参数会留痕并且能查到改前改后() {
        long operator = ops.userId();
        String before = opsApi.allSettings().stream()
                .filter(s -> s.key().equals(SettingKeys.CREDIT_RECENT_DAYS)).findFirst().orElseThrow().value();
        String after = String.valueOf(Integer.parseInt(before) + 1);

        opsApi.updateSetting(SettingKeys.CREDIT_RECENT_DAYS, after, "测试改动", operator);

        var changes = opsApi.settingChanges(SettingKeys.CREDIT_RECENT_DAYS, operator);
        assertThat(changes).isNotEmpty();
        var latest = changes.get(0);
        assertThat(latest.oldValue()).isEqualTo(before);
        assertThat(latest.newValue()).isEqualTo(after);
        assertThat(latest.changedBy()).isEqualTo(operator);
        assertThat(latest.reason()).isEqualTo("测试改动");

        // 改完立刻能读到新值(本实例缓存要失效)
        assertThat(opsApi.settingInt(SettingKeys.CREDIT_RECENT_DAYS, -1)).isEqualTo(Integer.parseInt(after));

        opsApi.updateSetting(SettingKeys.CREDIT_RECENT_DAYS, before, "改回去", operator);
    }

    @Test
    void 类型不符的值在入口就被拒绝() {
        // 存进去一个非法值,后果是热路径上每次读都退兜底 —— 要在入口挡住,不能等到读的时候。
        assertThatThrownBy(() -> opsApi.updateSetting(
                SettingKeys.WAGE_MIN_CENTS, "不是数字", "测试", ops.userId()))
                .hasMessageContaining("整数");
        assertThat(opsApi.settingInt(SettingKeys.WAGE_MIN_CENTS, -1)).isEqualTo(5_000);
    }

    @Test
    void 没有平台运维角色不能读也不能改参数() throws Exception {
        String token = plainToken("16900000001");
        mvc.perform(get("/api/ops/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/ops/settings/" + SettingKeys.BROKER_DEMOTION_DAYS)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"1\",\"reason\":\"越权尝试\"}"))
                .andExpect(status().isForbidden());
        // 看账不只看状态码:控制器回 403 但服务层已经写进去的话,只看响应发现不了
        assertThat(opsApi.settingInt(SettingKeys.BROKER_DEMOTION_DAYS, -1)).isEqualTo(90);
    }

    @Test
    void 平台运维能通过端点读到全部参数() throws Exception {
        mvc.perform(get("/api/ops/settings").header("Authorization", "Bearer " + opsToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 未登录访问参数端点一律401() throws Exception {
        mvc.perform(get("/api/ops/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ops/settings/changes")).andExpect(status().isUnauthorized());
    }
}
