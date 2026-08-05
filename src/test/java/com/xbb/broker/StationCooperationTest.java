package com.xbb.broker;

import com.xbb.TestcontainersConfig;
import com.xbb.broker.api.BrokerApi;
import com.xbb.identity.TestCodeAccessor;
import com.xbb.identity.TestPlatformOps;
import com.xbb.identity.api.IdentityApi;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 服务站与用工单位的合作(老系统 M9),以及合作下的操作员授权。
 *
 * <p>守的要害是**授权**:这个功能决定"谁能替谁办事"。
 * 「谁能发起」和「谁能确认」必须是两个不同的人,否则那个两步流程形同虚设。
 *
 * <p>号段 189,信用代码 …q xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class StationCooperationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired BrokerApi brokerApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    private record Sta(long orgId, long master) { }

    private Sta station(String phone, String idNo, String name, String code) {
        long master = verified(phone, "站长", idNo);
        long orgId = orgApi.createStation(name, code, ops.userId());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                orgApi.assignStationMaster(orgId, master, "指派", ops.userId()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId())).anyMatch(s -> s.orgId() == orgId));
        return new Sta(orgId, master);
    }

    private record Firm(long orgId, long boss) { }

    private Firm factory(String phone, String idNo, String name, String code) {
        long boss = verified(phone, "老板", idNo);
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.FACTORY, name, code, boss)));
        orgApi.approve(h.get(), ops.userId());
        return new Firm(h.get(), boss);
    }

    @Test
    void 申请到确认再到解除的完整流程() {
        Sta s = station("18900000001", "110101199001070371", "合作甲站", "9111000000000q01X");
        Firm f = factory("18900000002", "110101199001070372", "合作甲厂", "9111000000000q02X");

        long id = brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());
        assertThat(brokerApi.listCooperations(s.orgId(), s.master()))
                .singleElement().extracting(BrokerApi.CooperationView::status).isEqualTo("PENDING");

        brokerApi.confirmCooperation(id, f.boss());
        assertThat(brokerApi.listCooperations(f.orgId(), f.boss()))
                .anyMatch(c -> c.id() == id && c.status().equals("ACTIVE"));

        brokerApi.endCooperation(id, f.boss());
        assertThat(brokerApi.listCooperations(s.orgId(), s.master()))
                .anyMatch(c -> c.id() == id && c.status().equals("ENDED"));
    }

    @Test
    void 发起方不能自己确认() {
        Sta s = station("18900000003", "110101199001070373", "自确站", "9111000000000q03X");
        Firm f = factory("18900000004", "110101199001070374", "自确厂", "9111000000000q04X");
        long id = brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());

        // **少了这条,"申请—确认"两步就完全没有意义** —— 任何一方都能单方面建立合作
        assertThatThrownBy(() -> brokerApi.confirmCooperation(id, s.master()))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 服务站之间不能走合作要走联合() {
        Sta a = station("18900000005", "110101199001070375", "联合甲站", "9111000000000q05X");
        Sta b = station("18900000006", "110101199001070376", "联合乙站", "9111000000000q06X");
        // 两者的分账含义不同,混起来会让钱走错档
        assertThatThrownBy(() -> brokerApi.applyCooperation(a.orgId(), b.orgId(), true, a.master()))
                .hasMessageContaining("联合");
    }

    @Test
    void 解除合作会连带解绑操作员() {
        Sta s = station("18900000011", "110101199001070381", "操作员站", "9111000000000q07X");
        Firm f = factory("18900000012", "110101199001070382", "操作员厂", "9111000000000q08X");
        long id = brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());
        brokerApi.confirmCooperation(id, f.boss());

        long operator = verified("18900000013", "操作员", "110101199001070383");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                brokerApi.assignOperator(id, operator, s.master()));
        assertThat(brokerApi.listOperators(id, s.master())).hasSize(1);

        brokerApi.endCooperation(id, s.master());
        // **合作没了,授权也就没了。**留着的话那个人还挂着一份指向已结束合作的授权,
        // 而授权正是用来判断"他能不能替这家办事"的
        assertThat(brokerApi.listOperators(id, s.master()))
                .as("解除合作要连带解绑操作员").isEmpty();
    }

    @Test
    void 合作没生效不能指派操作员() {
        Sta s = station("18900000014", "110101199001070384", "未生效站", "9111000000000q09X");
        Firm f = factory("18900000015", "110101199001070385", "未生效厂", "9111000000000q10X");
        long id = brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());
        long operator = verified("18900000016", "操作员", "110101199001070386");

        // 合作还没谈成就先派人,那个人拿着的是一份不存在的授权
        assertThatThrownBy(() -> brokerApi.assignOperator(id, operator, s.master()))
                .hasMessageContaining("已生效");
    }

    @Test
    void 只有站长能指派操作员() {
        Sta s = station("18900000017", "110101199001070391", "越权站", "9111000000000q11X");
        Firm f = factory("18900000018", "110101199001070392", "越权厂", "9111000000000q12X");
        long id = brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());
        brokerApi.confirmCooperation(id, f.boss());
        long operator = verified("18900000019", "操作员", "110101199001070393");

        // 用工单位那边也不能派 —— 操作员代表的是服务站
        assertThatThrownBy(() -> brokerApi.assignOperator(id, operator, f.boss()))
                .hasMessageContaining("站长");
    }

    @Test
    void 路人看不到合作关系() {
        Sta s = station("18900000020", "110101199001070394", "保密站", "9111000000000q13X");
        Firm f = factory("18900000021", "110101199001070395", "保密厂", "9111000000000q14X");
        brokerApi.applyCooperation(s.orgId(), f.orgId(), true, s.master());
        long outsider = verified("18900000022", "路人", "110101199001070396");

        // 合作关系是两家的商业约定(铁律 5.1)
        assertThat(brokerApi.listCooperations(s.orgId(), outsider)).isEmpty();
        // 双方看得到 —— 挡住路人不能连正主一起挡掉
        assertThat(brokerApi.listCooperations(s.orgId(), s.master())).hasSize(1);
        assertThat(brokerApi.listCooperations(f.orgId(), f.boss())).hasSize(1);
    }
}
