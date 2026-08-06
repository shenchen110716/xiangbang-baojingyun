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
    @Autowired JobApi jobApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    @Test
    void 个人可以按总价发一单() {
        long poster = verified("13005000001", "张业主", "110101199001120001");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(jobApi.postJobByIndividual(poster, "搬家帮忙", "两室一厅,三楼",
                        50_000, "320506", "苏州市吴中区某小区 3 幢")));

        JobApi.JobView v = jobApi.findJob(h.get()).orElseThrow();
        assertThat(v.posterUserId()).isEqualTo(poster);
        assertThat(v.orgId()).isNull();
        assertThat(v.totalPriceCents()).isEqualTo(50_000);
        assertThat(v.regionCode()).isEqualTo("320506");
        assertThat(v.workAddress()).isEqualTo("苏州市吴中区某小区 3 幢");
    }

    @Test
    void 没实名不能发单() {
        long unverified = identityApi.loginByPhone("13005000002",
                codes.issue("13005000002")).userId();
        // 发单方要付钱、要签协议、出了纠纷要找得到人。没实名这些都无从追溯
        assertThatThrownBy(() ->
                jobApi.postJobByIndividual(unverified, "搬家", "描述", 50_000, "320506", "地址"))
                .hasMessageContaining("实名");
    }

    @Test
    void 个人发单必须填地区() {
        long poster = verified("13005000003", "李业主", "110101199001120003");
        // 没有地区就取不到佣金比例,这单结算时会卡住 ——
        // **与其那时候报错,不如发单时就拦**:那时候工人已经干完活了
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThatThrownBy(() ->
                        jobApi.postJobByIndividual(poster, "搬家", "描述", 50_000, "  ", "地址"))
                        .hasMessageContaining("地区"));
    }

    @Test
    void 总价必须为正() {
        long poster = verified("13005000004", "王业主", "110101199001120004");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            // 0 元的单子没有业务含义,负数更是会算出负佣金
            assertThatThrownBy(() ->
                    jobApi.postJobByIndividual(poster, "搬家", "描述", 0, "320506", "地址"))
                    .hasMessageContaining("总价");
            assertThatThrownBy(() ->
                    jobApi.postJobByIndividual(poster, "搬家", "描述", -1, "320506", "地址"))
                    .hasMessageContaining("总价");
        });
    }

    @Test
    void 个人发的单和企业发的单在同一个岗位列表里() {
        long poster = verified("13005000005", "赵业主", "110101199001120005");
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(jobApi.postJobByIndividual(poster, "个人单也要能被搜到", "描述",
                        30_000, "320500", "地址")));

        // 求职端只有一个"找活"入口。个人单不在里面的话,发了也没人看得到
        assertThat(jobApi.listOpenJobs(100))
                .anyMatch(j -> j.id() == h.get() && j.posterUserId() != null);
    }
}
