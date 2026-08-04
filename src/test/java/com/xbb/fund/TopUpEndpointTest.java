package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监管账户入账端点的守卫。
 *
 * <p>这是**唯一一个能凭空增加系统内金额**的 HTTP 入口,所以三件事都得守住:
 * 谁能调、能不能重复、金额合不合法。
 *
 * <p>其中"重复"最要命:出账一直有幂等键,入账此前完全没有。
 * 网络超时重试或用户双击,不带键就是又造一笔钱,而账面上看不出异常——
 * 余额多了,流水里两条一模一样的入账,事后无从分辨哪条是重复的。
 *
 * <p>号段 168。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class TopUpEndpointTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired FundApi fundApi;

    private String opsToken() {
        ops.userId();   // 确保角色已授予
        return identityApi.loginByPhone(TestPlatformOps.Accessor.PHONE,
                codes.issue(TestPlatformOps.Accessor.PHONE)).token();
    }

    private String plainToken(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).token();
    }

    private String body(long cents, String key) {
        return "{\"amountCents\":" + cents + ",\"reason\":\"测试入账\",\"idempotencyKey\":\"" + key + "\"}";
    }

    @Test
    void 同一幂等键重复入账只加一次() throws Exception {
        long before = fundApi.balanceOf(AccountType.GUARANTEE_POOL);
        String token = opsToken();
        String key = "topup-test-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/fund/accounts/GUARANTEE_POOL/top-up")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(50_000, key)))
                    .andExpect(status().isOk());
        }

        // 打了三次,只能多 50000 分。断言余额而不是断言"没报错"——
        // 三次都返回 200 才是正常表现,报错反而说明幂等做成了"拒绝重试"。
        assertThat(fundApi.balanceOf(AccountType.GUARANTEE_POOL)).isEqualTo(before + 50_000);
    }

    @Test
    void 不同幂等键各加一次() throws Exception {
        long before = fundApi.balanceOf(AccountType.PLATFORM_REVENUE);
        String token = opsToken();
        long n = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/fund/accounts/PLATFORM_REVENUE/top-up")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(10_000, "topup-diff-" + n + "-" + i)))
                    .andExpect(status().isOk());
        }
        assertThat(fundApi.balanceOf(AccountType.PLATFORM_REVENUE)).isEqualTo(before + 20_000);
    }

    @Test
    void 没有平台运维角色不能入账() throws Exception {
        long before = fundApi.balanceOf(AccountType.USER_FUNDS);
        mvc.perform(post("/api/fund/accounts/USER_FUNDS/top-up")
                        .header("Authorization", "Bearer " + plainToken("16800000001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(99_999, "topup-denied-" + System.nanoTime())))
                .andExpect(status().is4xxClientError());
        // 状态码之外还要看账:控制器回 4xx 但服务层已经写进去的话,只看响应发现不了
        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(before);
    }

    @Test
    void 缺幂等键或金额非正被拒() throws Exception {
        String token = opsToken();
        long before = fundApi.balanceOf(AccountType.USER_FUNDS);

        mvc.perform(post("/api/fund/accounts/USER_FUNDS/top-up")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\":1000,\"reason\":\"没键\"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(post("/api/fund/accounts/USER_FUNDS/top-up")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, "topup-zero-" + System.nanoTime())))
                .andExpect(status().is4xxClientError());

        mvc.perform(post("/api/fund/accounts/USER_FUNDS/top-up")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(-5000, "topup-neg-" + System.nanoTime())))
                .andExpect(status().is4xxClientError());

        assertThat(fundApi.balanceOf(AccountType.USER_FUNDS)).isEqualTo(before);
    }

    @Test
    void 未登录不能入账() throws Exception {
        mvc.perform(post("/api/fund/accounts/USER_FUNDS/top-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1000, "topup-anon-" + System.nanoTime())))
                .andExpect(status().isUnauthorized());
    }
}
