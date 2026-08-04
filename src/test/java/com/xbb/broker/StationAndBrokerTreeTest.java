package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.broker.internal.StationRepository;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
import com.xbb.ops.api.OpsApi;
import com.xbb.ops.api.SettingKeys;
import com.xbb.org.api.OrgApi;
import com.xbb.org.internal.Organization;
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
 * 服务站副本与业务员网络的守卫。
 *
 * <p>号段 171,信用代码 …7xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class StationAndBrokerTreeTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired OpsApi opsApi;
    @Autowired BrokerApi brokerApi;
    @Autowired StationRepository stations;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    /** 提交并通过审核。实名副本是异步落地的,提交要等它到位。 */
    private long approvedOrg(long legalRep, com.xbb.org.api.OrgType type, String name, String code) {
        AtomicLong holder = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                holder.set(orgApi.submit(type, name, code, legalRep)));
        long orgId = holder.get();
        orgApi.approve(orgId, ops.userId());
        return orgId;
    }

    /** 注册业务员。实名副本经 outbox 异步到达本域,注册太快会被"未实名"挡住。 */
    private long broker(String phone, String name, String idNo) {
        long id = verified(phone, name, idNo);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> brokerApi.registerBroker(id));
        return id;
    }

    @Test
    void 只有服务站会进本域副本_企业和工厂不会() {
        long rep = verified("17100000001", "站长甲", "110101199001010701");
        long stationOrg = approvedOrg(rep, com.xbb.org.api.OrgType.SERVICE_STATION, "甲服务站", "91110000000000701X");
        long factoryOrg = approvedOrg(rep, com.xbb.org.api.OrgType.FACTORY, "甲工厂", "91110000000000702X");

        await().atMost(Duration.ofSeconds(15))
                .until(() -> stations.findById(stationOrg).isPresent());
        // 成对验:服务站进来了 **且** 工厂没进来。
        // 只验前半段的话,一个"照单全收"的监听器也能让测试通过。
        assertThat(stations.findById(factoryOrg)).as("工厂不该被当成服务站").isEmpty();
    }

    @Test
    void 服务站比例为空时跟随平台默认() {
        long rep = verified("17100000002", "站长乙", "110101199001010702");
        long stationOrg = approvedOrg(rep, com.xbb.org.api.OrgType.SERVICE_STATION, "乙服务站", "91110000000000703X");
        await().atMost(Duration.ofSeconds(15)).until(() -> stations.findById(stationOrg).isPresent());

        int platformDefault = (int) opsApi.settingInt(SettingKeys.COMMISSION_STATION_PERCENT, -1);
        var view = brokerApi.listStations(ops.userId()).stream()
                .filter(s -> s.orgId() == stationOrg).findFirst().orElseThrow();
        assertThat(view.stationPercent()).as("没单独设过应当是 null").isNull();
        assertThat(view.effectivePercent()).isEqualTo(platformDefault);

        // 单独设过之后就不跟随了
        brokerApi.setStationPercent(stationOrg, 35, "测试", ops.userId());
        var after = brokerApi.listStations(ops.userId()).stream()
                .filter(s -> s.orgId() == stationOrg).findFirst().orElseThrow();
        assertThat(after.stationPercent()).isEqualTo(35);
        assertThat(after.effectivePercent()).isEqualTo(35);

        // 传 null 是"改回跟随默认",不是"设成 0"
        brokerApi.setStationPercent(stationOrg, null, "改回跟随", ops.userId());
        var back = brokerApi.listStations(ops.userId()).stream()
                .filter(s -> s.orgId() == stationOrg).findFirst().orElseThrow();
        assertThat(back.stationPercent()).isNull();
        assertThat(back.effectivePercent()).isEqualTo(platformDefault);
    }

    @Test
    void 挂靠服务站与改上级都会留痕() {
        long rep = verified("17100000003", "站长丙", "110101199001010703");
        long stationOrg = approvedOrg(rep, com.xbb.org.api.OrgType.SERVICE_STATION, "丙服务站", "91110000000000704X");
        await().atMost(Duration.ofSeconds(15)).until(() -> stations.findById(stationOrg).isPresent());

        long boss = broker("17100000004", "业务员丁", "110101199001010704");
        long staff = broker("17100000005", "业务员戊", "110101199001010705");

        brokerApi.assignStation(staff, stationOrg, "新人入站", ops.userId());
        brokerApi.assignParent(staff, boss, "指派上级", ops.userId());

        var changes = brokerApi.brokerChanges(staff, ops.userId());
        assertThat(changes).hasSize(2);
        assertThat(changes).extracting(BrokerApi.BrokerChangeView::changeType)
                .containsExactlyInAnyOrder("STATION", "PARENT");
        assertThat(changes).allSatisfy(c -> {
            assertThat(c.changedBy()).isEqualTo(ops.userId());
            assertThat(c.reason()).isNotBlank();
        });

        var node = brokerApi.listBrokers(stationOrg, ops.userId()).stream()
                .filter(b -> b.userId() == staff).findFirst().orElseThrow();
        assertThat(node.stationOrgId()).isEqualTo(stationOrg);
        assertThat(node.parentUserId()).isEqualTo(boss);
    }

    @Test
    void 把上级挂到自己下级名下会被拒绝_防成环() {
        long a = broker("17100000006", "业务员己", "110101199001010706");
        long b = broker("17100000007", "业务员庚", "110101199001010707");
        long c = broker("17100000008", "业务员辛", "110101199001010708");

        brokerApi.assignParent(b, a, "建链", ops.userId());   // a → b
        brokerApi.assignParent(c, b, "建链", ops.userId());   // a → b → c

        // 把 a 挂到 c 下面就成了 a→b→c→a,沿链分佣金会死循环
        assertThatThrownBy(() -> brokerApi.assignParent(a, c, "制造闭环", ops.userId()))
                .hasMessageContaining("闭环");

        // 自己当自己上级同样拒绝
        assertThatThrownBy(() -> brokerApi.assignParent(a, a, "自环", ops.userId()))
                .hasMessageContaining("自己");
    }

    @Test
    void 变更必须填理由() {
        long x = broker("17100000009", "业务员壬", "110101199001010709");
        assertThatThrownBy(() -> brokerApi.assignParent(x, null, "  ", ops.userId()))
                .hasMessageContaining("理由");
    }

    @Test
    void 没有平台运维角色不能改归属也不能看列表() {
        long outsider = verified("17100000010", "路人癸", "110101199001010710");
        long x = broker("17100000011", "业务员子", "110101199001010711");

        assertThatThrownBy(() -> brokerApi.listStations(outsider))
                .isInstanceOfAny(IllegalStateException.class,
                        org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> brokerApi.assignStation(x, null, "越权", outsider))
                .isInstanceOfAny(IllegalStateException.class,
                        org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void 根业务员的上级为空() {
        long root = broker("17100000012", "业务员丑", "110101199001010712");
        var node = brokerApi.listBrokers(null, ops.userId()).stream()
                .filter(b -> b.userId() == root).findFirst().orElseThrow();
        assertThat(node.parentUserId()).as("刚注册的业务员是根,不参与降级").isNull();
        assertThat(node.status()).isEqualTo("ACTIVE");
    }
}
