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
 * 服务站间联合(老系统 M10 §3.4 StationJoint)。
 *
 * <p>守的主要是**授权**:这个功能的本质是"把自己的佣金分一部分给别人",
 * 所以"谁能发起"和"谁能确认"必须是两个不同的人 —— 否则那个两步流程形同虚设。
 *
 * <p>号段 181,信用代码 …h xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class StationJointTest {

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

    private record Sta(long orgId, long legalRep) { }

    /** 建一个已审核的服务站,并等它的副本落到经纪人域。 */
    private Sta station(String phone, String idNo, String name, String creditCode) {
        long legalRep = verified(phone, "站长", idNo);
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.SERVICE_STATION, name, creditCode, legalRep)));
        long orgId = h.get();
        orgApi.approve(orgId, ops.userId());
        // 服务站副本经 outbox 异步到达经纪人域,不等的话下面全是"服务站不存在"
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(brokerApi.listStations(ops.userId()))
                        .anyMatch(st -> st.orgId() == orgId));
        return new Sta(orgId, legalRep);
    }

    @Test
    void 申请到确认再到解除的完整流程() {
        Sta a = station("18100000001", "110101199001030014", "联合甲站", "9111000000000h01X");
        Sta b = station("18100000002", "110101199001030015", "联合乙站", "9111000000000h02X");

        long jointId = brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());
        assertThat(brokerApi.listJoints(a.orgId(), a.legalRep()))
                .singleElement().extracting(BrokerApi.StationJointView::status).isEqualTo("PENDING");

        brokerApi.confirmJoint(jointId, b.legalRep());
        var active = brokerApi.listJoints(a.orgId(), a.legalRep()).get(0);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.confirmedAt()).isNotNull();
        assertThat(active.ratePercent()).isEqualTo(30);

        brokerApi.endJoint(jointId, b.legalRep());
        var ended = brokerApi.listJoints(a.orgId(), a.legalRep()).get(0);
        // **解除不删行** —— 历史佣金要靠它解释当初为什么分给了那个站
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.endedAt()).isNotNull();
    }

    @Test
    void 发起方不能自己确认() {
        Sta a = station("18100000003", "110101199001030016", "自确甲站", "9111000000000h03X");
        Sta b = station("18100000004", "110101199001030017", "自确乙站", "9111000000000h04X");
        long jointId = brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());

        // **少了这条,"申请—确认"两步就完全没有意义** ——
        // 任何站都能单方面给自己安排一份别人的佣金
        assertThatThrownBy(() -> brokerApi.confirmJoint(jointId, a.legalRep()))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 不是站长不能替服务站发起联合() {
        Sta a = station("18100000005", "110101199001030018", "越权甲站", "9111000000000h05X");
        Sta b = station("18100000006", "110101199001030019", "越权乙站", "9111000000000h06X");
        long outsider = verified("18100000007", "路人", "110101199001030020");

        // 这一步是在决定把别人的佣金分出去
        assertThatThrownBy(() -> brokerApi.applyJoint(a.orgId(), b.orgId(), 30, outsider))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 不能和自己联合() {
        Sta a = station("18100000008", "110101199001030021", "自联站", "9111000000000h07X");
        // 放进来的话分账会把钱切给自己再加回来:金额看着对,
        // 但流水里凭空多出两条互相抵消的记录,对账时没人看得懂
        assertThatThrownBy(() -> brokerApi.applyJoint(a.orgId(), a.orgId(), 30, a.legalRep()))
                .hasMessageContaining("不能和自己");
    }

    @Test
    void 比例必须在合理区间() {
        Sta a = station("18100000009", "110101199001030022", "比例甲站", "9111000000000h08X");
        Sta b = station("18100000010", "110101199001030023", "比例乙站", "9111000000000h09X");

        // 0% 没有业务含义;100% 意味着发起方一分不留,多半是填错了
        assertThatThrownBy(() -> brokerApi.applyJoint(a.orgId(), b.orgId(), 0, a.legalRep()))
                .hasMessageContaining("比例");
        assertThatThrownBy(() -> brokerApi.applyJoint(a.orgId(), b.orgId(), 100, a.legalRep()))
                .hasMessageContaining("比例");
    }

    @Test
    void 同一对服务站不能重复发起() {
        Sta a = station("18100000011", "110101199001030024", "重复甲站", "9111000000000h10X");
        Sta b = station("18100000012", "110101199001030025", "重复乙站", "9111000000000h11X");
        brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());

        // 老系统靠"已申请则拦截重复"在应用层判,并发下无效。这里由唯一索引兜底
        assertThatThrownBy(() -> brokerApi.applyJoint(a.orgId(), b.orgId(), 40, a.legalRep()))
                .hasMessageContaining("不能重复发起");
    }

    @Test
    void 已联合后反向发起会被拦() {
        Sta a = station("18100000013", "110101199001030026", "反向甲站", "9111000000000h12X");
        Sta b = station("18100000014", "110101199001030027", "反向乙站", "9111000000000h13X");
        long jointId = brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());
        brokerApi.confirmJoint(jointId, b.legalRep());

        // A→B 和 B→A 同时生效会让两个站互相分成,钱在两边来回切;
        // 总额虽然不超,但明细没人看得懂
        assertThatThrownBy(() -> brokerApi.applyJoint(b.orgId(), a.orgId(), 20, b.legalRep()))
                .hasMessageContaining("已经和你联合");
    }

    @Test
    void 已生效的联合不能用撤回_要走解除() {
        Sta a = station("18100000015", "110101199001030028", "撤回甲站", "9111000000000h14X");
        Sta b = station("18100000016", "110101199001030029", "撤回乙站", "9111000000000h15X");
        long jointId = brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());
        brokerApi.confirmJoint(jointId, b.legalRep());

        assertThatThrownBy(() -> brokerApi.cancelJoint(jointId, a.legalRep()))
                .hasMessageContaining("解除");
    }

    @Test
    void 解除后可以重新发起() {
        Sta a = station("18100000017", "110101199001030030", "重来甲站", "9111000000000h16X");
        Sta b = station("18100000018", "110101199001030031", "重来乙站", "9111000000000h17X");
        long first = brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());
        brokerApi.confirmJoint(first, b.legalRep());
        brokerApi.endJoint(first, a.legalRep());

        // 唯一索引只约束未结束的那些。解除后谈不拢再谈是正常业务,
        // 不该被一条历史记录永久挡住
        long second = brokerApi.applyJoint(a.orgId(), b.orgId(), 25, a.legalRep());
        assertThat(second).isNotEqualTo(first);
        assertThat(brokerApi.listJoints(a.orgId(), a.legalRep())).hasSize(2);
    }

    @Test
    void 路人看不到联合关系() {
        Sta a = station("18100000019", "110101199001030032", "可见甲站", "9111000000000h18X");
        Sta b = station("18100000020", "110101199001030033", "可见乙站", "9111000000000h19X");
        long outsider = verified("18100000021", "路人", "110101199001030034");
        brokerApi.applyJoint(a.orgId(), b.orgId(), 30, a.legalRep());

        // 分成比例是两家的商业约定(见铁律 5.1)
        assertThat(brokerApi.listJoints(a.orgId(), outsider)).isEmpty();
        // 双方和平台运维看得到 —— 挡住路人不能连正主一起挡掉
        assertThat(brokerApi.listJoints(a.orgId(), a.legalRep())).hasSize(1);
        assertThat(brokerApi.listJoints(b.orgId(), b.legalRep())).hasSize(1);
        assertThat(brokerApi.listJoints(a.orgId(), ops.userId())).hasSize(1);
    }
}
