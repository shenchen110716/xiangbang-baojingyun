package com.xbb.job;

import com.xbb.TestcontainersConfig;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.job.api.JobApi;
import com.xbb.org.api.OrgApi;
import com.xbb.org.api.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 每个岗位可以有自己的工作地点(老板 2026-08-06)。
 *
 * <p>灵活用工里同一家单位可能在好几个工地同时开工 ——
 * 只有单位注册地址的话,**求职者看到的是总部地址,跑错地方**。
 *
 * <p>号段 13002,信用代码 …u xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class JobWorkAddressTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired JobApi jobApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    /** 建一个已审核、带注册地址的工厂。 */
    private long factory(String phone, String idNo, String name, String code, String address) {
        long rep = verified(phone, "法人", idNo);
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submitWithAddress(OrgType.FACTORY, name, code, rep, address)));
        orgApi.approve(h.get(), ops.userId());
        return h.get();
    }

    private long repOf(String phone) {
        return identityApi.loginByPhone(phone, codes.issue(phone)).userId();
    }

    @Test
    void 岗位自己的工作地点盖过单位注册地址() {
        long orgId = factory("13002000001", "110101199001090001",
                "多工地建设集团", "9111000000000u01X", "苏州市姑苏区总部大厦");
        long rep = repOf("13002000001");

        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                h.set(jobApi.postJob(orgId, "工地钢筋工", "绑扎钢筋", 3000, 5,
                        "苏州市吴中区太湖大道 99 号 3 号工地", rep)));

        JobApi.JobView v = jobApi.findJob(h.get()).orElseThrow();
        assertThat(v.workAddress()).isEqualTo("苏州市吴中区太湖大道 99 号 3 号工地");
        // 单位地址仍然带着 —— 两个是不同的东西,不能拿一个顶替另一个
        assertThat(v.orgAddress()).isEqualTo("苏州市姑苏区总部大厦");
    }

    @Test
    void 同一家单位的两个岗位可以在不同地址() {
        long orgId = factory("13002000002", "110101199001090002",
                "两处开工的厂", "9111000000000u02X", "注册地址");
        long rep = repOf("13002000002");

        AtomicLong a = new AtomicLong();
        AtomicLong b = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                a.set(jobApi.postJob(orgId, "东厂区分拣", "分拣", 2000, 1, "东厂区 A 门", rep)));
        b.set(jobApi.postJob(orgId, "西厂区分拣", "分拣", 2000, 1, "西厂区 B 门", rep));

        // **这条是这次改动的全部理由。**只有单位地址的话,
        // 两个岗位在界面上长得一模一样,工人跑错地方
        assertThat(jobApi.findJob(a.get()).orElseThrow().workAddress()).isEqualTo("东厂区 A 门");
        assertThat(jobApi.findJob(b.get()).orElseThrow().workAddress()).isEqualTo("西厂区 B 门");
    }

    @Test
    void 不填工作地点时为空而不是抄一份单位地址() {
        long orgId = factory("13002000003", "110101199001090003",
                "老岗位的厂", "9111000000000u03X", "单位注册地址");
        long rep = repOf("13002000003");

        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                h.set(jobApi.postJob(orgId, "没填地址的岗", "描述", 1800, rep)));

        JobApi.JobView v = jobApi.findJob(h.get()).orElseThrow();
        // 在这里抄一份的话,**以后单位改了地址,这个岗位还留着旧的**,
        // 而且没人知道那是抄来的还是真填的。
        // 要显示什么由展示层决定(客户端:工作地点为空就退回单位地址)
        assertThat(v.workAddress()).isNull();
        assertThat(v.orgAddress()).isEqualTo("单位注册地址");
    }

    @Test
    void 只有空白的地址当作没填() {
        long orgId = factory("13002000004", "110101199001090004",
                "空白地址的厂", "9111000000000u04X", null);
        long rep = repOf("13002000004");

        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                h.set(jobApi.postJob(orgId, "空白地址岗", "描述", 1800, 1, "   ", rep)));

        // 留着空串的话,"有没有地址"变成两种判断,展示层的退回逻辑会漏一种
        assertThat(jobApi.findJob(h.get()).orElseThrow().workAddress()).isNull();
    }
}
