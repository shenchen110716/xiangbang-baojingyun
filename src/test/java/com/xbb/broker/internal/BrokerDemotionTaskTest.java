package com.xbb.broker.internal;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 业务员降级任务的守卫。
 *
 * <p>照搬老系统的语义,两处刻意不同:**无下级的不物理删除**(标 DEMOTED)、
 * **每一步都留痕**。这两条正是这里要守住的 —— 老系统删了就没了,
 * 而删掉之后他名下已产生的佣金归属就断了。
 *
 * <p>把活跃时间往前拨用的是 JDBC 直改,而不是给实体加一个只有测试用的 setter ——
 * 生产代码里不该有只为测试存在的口子。
 *
 * <p>号段 172。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class BrokerDemotionTaskTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired BrokerApi brokerApi;
    @Autowired OpsApi opsApi;
    @Autowired BrokerRepository brokers;
    @Autowired BrokerChangeLogRepository changeLogs;
    @Autowired BrokerDemotionTask task;
    @Autowired @Qualifier("brokerDataSource") DataSource brokerDataSource;

    private JdbcTemplate jdbc() { return new JdbcTemplate(brokerDataSource); }

    private long broker(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        // 实名副本经 outbox 异步到达本域
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> brokerApi.registerBroker(id));
        return id;
    }

    /** 把活跃时间往前拨,制造"长期不活跃"。 */
    private void backdate(long userId, int days) {
        jdbc().update("UPDATE broker.broker SET last_active_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)), userId);
    }

    @Test
    void 无下级且长期不活跃_标记降级而不是删除() {
        long root = broker("17200000001", "根甲", "110101199001010801");
        long leaf = broker("17200000002", "叶乙", "110101199001010802");
        brokerApi.assignParent(leaf, root, "建链", ops.userId());
        backdate(leaf, 200);

        task.run();

        // **这条是和老系统最大的差别**:记录还在,只是状态变了。
        // 删掉的话,他名下已产生的佣金归属就断了,出纠纷查不回来。
        var after = brokers.findById(leaf);
        assertThat(after).as("降级不该物理删除").isPresent();
        assertThat(after.orElseThrow().getStatus()).isEqualTo(Broker.Status.DEMOTED);

        var logs = changeLogs.findByBrokerUserIdOrderByChangedAtDesc(leaf);
        assertThat(logs).isNotEmpty();
        var latest = logs.get(0);
        assertThat(latest.getChangeType()).isEqualTo(BrokerChangeLog.ChangeType.STATUS);
        assertThat(latest.getNewValue()).isEqualTo("DEMOTED");
        assertThat(latest.getChangedBy()).as("系统自动降级没有操作人,记 null 而不是编一个 0").isNull();
    }

    @Test
    void 有下级的被架空_下级上提且自己重置活跃时间() {
        long grand = broker("17200000003", "祖丙", "110101199001010803");
        long mid   = broker("17200000004", "中丁", "110101199001010804");
        long leaf  = broker("17200000005", "叶戊", "110101199001010805");
        brokerApi.assignParent(mid, grand, "建链", ops.userId());
        brokerApi.assignParent(leaf, mid, "建链", ops.userId());
        backdate(mid, 200);

        task.run();

        // 下级上提到祖父
        assertThat(brokers.findById(leaf).orElseThrow().getParentUserId()).isEqualTo(grand);
        // 自己**没被降级**,只是活跃时间重置 —— 相当于缓刑一轮,这是老系统刻意的设计
        Broker after = brokers.findById(mid).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Broker.Status.ACTIVE);
        assertThat(after.getLastActiveAt()).isAfter(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @Test
    void 根业务员永不降级() {
        long root = broker("17200000006", "根己", "110101199001010806");
        backdate(root, 999);

        task.run();

        // 老系统用 parBrokerId != 0 表达同一条豁免
        assertThat(brokers.findById(root).orElseThrow().getStatus()).isEqualTo(Broker.Status.ACTIVE);
    }

    @Test
    void 活跃的人不会被降级() {
        long root = broker("17200000007", "根庚", "110101199001010807");
        long fresh = broker("17200000008", "新辛", "110101199001010808");
        brokerApi.assignParent(fresh, root, "建链", ops.userId());
        // 不往前拨:刚注册就是活跃的

        task.run();

        assertThat(brokers.findById(fresh).orElseThrow().getStatus()).isEqualTo(Broker.Status.ACTIVE);
    }

    @Test
    void 已降级的不会被重复处理() {
        long root = broker("17200000009", "根壬", "110101199001010809");
        long leaf = broker("17200000010", "叶癸", "110101199001010810");
        brokerApi.assignParent(leaf, root, "建链", ops.userId());
        backdate(leaf, 200);

        task.run();
        int logsAfterFirst = changeLogs.findByBrokerUserIdOrderByChangedAtDesc(leaf).size();
        task.run();

        // 重复处理会刷出一堆一模一样的留痕,把真实的变更淹掉
        assertThat(changeLogs.findByBrokerUserIdOrderByChangedAtDesc(leaf)).hasSize(logsAfterFirst);
    }

    @Test
    void 降级天数改小之后更多人会被捞出来() {
        long root = broker("17200000011", "根子", "110101199001010811");
        long leaf = broker("17200000012", "叶丑", "110101199001010812");
        brokerApi.assignParent(leaf, root, "建链", ops.userId());
        backdate(leaf, 30);          // 30 天,默认阈值 90 天捞不到

        task.run();
        assertThat(brokers.findById(leaf).orElseThrow().getStatus())
                .as("30 天 < 默认 90 天,不该被降级").isEqualTo(Broker.Status.ACTIVE);

        // 参数中心把天数改成 10 —— 这就是"参数可配"的意义
        String before = String.valueOf(opsApi.settingInt(SettingKeys.BROKER_DEMOTION_DAYS, 90));
        opsApi.updateSetting(SettingKeys.BROKER_DEMOTION_DAYS, "10", "测试:调小阈值", ops.userId());
        try {
            task.run();
            assertThat(brokers.findById(leaf).orElseThrow().getStatus())
                    .isEqualTo(Broker.Status.DEMOTED);
        } finally {
            opsApi.updateSetting(SettingKeys.BROKER_DEMOTION_DAYS, before, "测试完毕改回", ops.userId());
        }
    }

    @Test
    void 天数配成零时整体跳过而不是把所有人降级() {
        // 0 天意味着"阈值 = 现在",不加这个保护会把**全部非根业务员**一次降完。
        // 想停用降级应该把天数设得很大,而不是设成 0 —— 但配置错误必须挡住。
        long root = broker("17200000013", "根寅", "110101199001010813");
        long leaf = broker("17200000014", "叶卯", "110101199001010814");
        brokerApi.assignParent(leaf, root, "建链", ops.userId());
        backdate(leaf, 200);

        String before = String.valueOf(opsApi.settingInt(SettingKeys.BROKER_DEMOTION_DAYS, 90));
        opsApi.updateSetting(SettingKeys.BROKER_DEMOTION_DAYS, "0", "测试:错误配置", ops.userId());
        try {
            assertThat(task.run()).isZero();
            assertThat(brokers.findById(leaf).orElseThrow().getStatus()).isEqualTo(Broker.Status.ACTIVE);
        } finally {
            opsApi.updateSetting(SettingKeys.BROKER_DEMOTION_DAYS, before, "测试完毕改回", ops.userId());
        }
    }
}
