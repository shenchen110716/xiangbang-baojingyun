package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 个人发单(老板 2026-08-06)。
 *
 * <p>个人只填**总价**,员工价和佣金由平台按「类目 + 地区」的比例算出来。
 *
 * <p>守的要害是**发单方恰好一个**、以及**个人必须填总价和地区**。
 * 后者不守的话,这单要等到结算时才发现取不到佣金比例 ——
 * 那时候工人已经干完活了。
 *
 * <p>号段 13005,信用代码 …o xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class IndividualPostingTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired JobApi jobApi;
    @Autowired com.xbb.broker.api.BrokerApi brokerApi;

    /** 先配好比例 —— 没配的话发单会被拦下(那正是设计要的)。 */
    private void rate(String region, int commissionPct) {
        brokerApi.setCommissionRate(com.xbb.broker.api.RateCategory.JOB, region,
                commissionPct, 0, null, "测试配置", ops.userId());
    }

    /**
     * 走和控制器一样的组装路径:先问经纪人域拆总价,再交给岗位域。
     * <b>测试自己拼一组数字的话,就绕过了真正会跑的那条路。</b>
     */
    private long post(long poster, String title, long totalPriceCents,
                       String region, String address) {
        var split = brokerApi.splitTotalPrice(
                com.xbb.broker.api.RateCategory.JOB, region, totalPriceCents);
        return jobApi.postJobByIndividual(poster, title, "描述",
                totalPriceCents, region, address,
                split.workerCents(), split.commissionCents(),
                split.dispatchRetainCents(), split.dispatchOrgId());
    }

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    @Test
    void 个人可以按总价发一单() {
        rate("320506", 10);
        long poster = verified("13005000001", "张业主", "110101199001120001");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(post(poster, "搬家帮忙", 50_000, "320506", "苏州市吴中区某小区 3 幢")));

        JobApi.JobView v = jobApi.findJob(h.get()).orElseThrow();
        assertThat(v.posterUserId()).isEqualTo(poster);
        assertThat(v.orgId()).isNull();
        assertThat(v.totalPriceCents()).isEqualTo(50_000);
        assertThat(v.regionCode()).isEqualTo("320506");
        assertThat(v.workAddress()).isEqualTo("苏州市吴中区某小区 3 幢");
    }

    @Test
    void 没实名不能发单() {
        rate("320506", 10);
        long unverified = identityApi.loginByPhone("13005000002",
                codes.issue("13005000002")).userId();
        // 发单方要付钱、要签协议、出了纠纷要找得到人。没实名这些都无从追溯
        assertThatThrownBy(() ->
                post(unverified, "搬家", 50_000, "320506", "地址"))
                .hasMessageContaining("实名");
    }

    @Test
    void 个人发单必须填地区() {
        rate("320506", 10);
        long poster = verified("13005000003", "李业主", "110101199001120003");
        // 没有地区就取不到佣金比例,这单结算时会卡住 ——
        // **与其那时候报错,不如发单时就拦**:那时候工人已经干完活了
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() ->
                        post(poster, "搬家", 50_000, "  ", "地址"))
                        .hasMessageContaining("地区"));
    }

    @Test
    void 总价必须为正() {
        rate("320506", 10);
        long poster = verified("13005000004", "王业主", "110101199001120004");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            // 0 元的单子没有业务含义,负数更是会算出负佣金
            assertThatThrownBy(() ->
                    post(poster, "搬家", 0, "320506", "地址"))
                    .hasMessageContaining("总价");
            assertThatThrownBy(() ->
                    post(poster, "搬家", -1, "320506", "地址"))
                    .hasMessageContaining("总价");
        });
    }

    @Test
    void 个人发的单和企业发的单在同一个岗位列表里() {
        rate("320500", 10);
        long poster = verified("13005000005", "赵业主", "110101199001120005");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(post(poster, "个人单也要能被搜到", 30_000, "320500", "地址")));

        // 求职端只有一个"找活"入口。个人单不在里面的话,发了也没人看得到
        assertThat(jobApi.listOpenJobs(100))
                .anyMatch(j -> j.id() == h.get() && j.posterUserId() != null);
    }

    // ─────────────── 发单时定死分账 ───────────────

    @Test
    void 三段加起来正好是总价() {
        rate("330100", 10);
        long poster = verified("13005000006", "钱业主", "110101199001120006");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(post(poster, "总价单", 100_000, "330100", "地址")));

        JobApi.JobView v = jobApi.findJob(h.get()).orElseThrow();
        assertThat(v.totalPriceCents()).isEqualTo(100_000);
        // 佣金 10% = 10000,员工价 = 90000
        assertThat(v.workerCents()).isEqualTo(90_000);
        // **工人拿到的就是 wageCents 这个字段。**存 0 的话结算出来是 0 元,
        // 而界面上显示的是总价 —— 两边对不上,且只有发工资时才发现
        assertThat(v.wageCents()).isEqualTo(90_000);
    }

    @Test
    void 没配比例时发单被拦_而不是发出一张结算时才卡住的单() {
        long poster = verified("13005000007", "孙业主", "110101199001120007");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() ->
                        post(poster, "没比例的地区", 50_000, "889900", "地址"))
                        // 那时候工人已经干完活了
                        .hasMessageContaining("没有配佣金比例"));
    }

    @Test
    void 发单后改比例_已发的单纹丝不动() {
        rate("340100", 10);
        long poster = verified("13005000008", "周业主", "110101199001120008");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(post(poster, "锁价单", 100_000, "340100", "地址")));
        assertThat(jobApi.findJob(h.get()).orElseThrow().workerCents()).isEqualTo(90_000);

        // 平台把比例调到 40%
        rate("340100", 40);

        // **工人是看着"这单 900 元"才接的。**结算时重算的话他拿到手变成 600,
        // 而他不会知道为什么少了
        assertThat(jobApi.findJob(h.get()).orElseThrow().workerCents())
                .as("已发出的单不该被后来的比例调整改动")
                .isEqualTo(90_000);
    }
}
