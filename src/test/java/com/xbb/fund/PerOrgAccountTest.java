package com.xbb.fund;

import com.xbb.TestcontainersConfig;
import com.xbb.fund.api.AccountType;
import com.xbb.fund.api.FundApi;
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
 * 资金账户按单位分账(老板 2026-08-06 选了乙)。
 *
 * <p>守两件事:**钱只能从自己那家扣**,以及**只有自己人能动**。
 * 前者错了是把 A 公司的钱发给 B 公司的工人;
 * 后者错了是任何人都能替别家承担用工责任。
 *
 * <p>号段 13004,信用代码 …l xx。
 */
@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class, TestPlatformOps.class})
class PerOrgAccountTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired IdentityApi identityApi;
    @Autowired TestCodeAccessor codes;
    @Autowired TestPlatformOps.Accessor ops;
    @Autowired OrgApi orgApi;
    @Autowired FundApi fundApi;
    @Autowired com.xbb.fund.api.AdvanceApi advanceApi;

    private long verified(String phone, String name, String idNo) {
        long id = identityApi.loginByPhone(phone, codes.issue(phone)).userId();
        identityApi.verifyRealName(id, name, idNo);
        return id;
    }

    private record Org(long orgId, long rep) { }

    private Org org(String phone, String idNo, String name, String code) {
        long rep = verified(phone, "法人", idNo);
        AtomicLong h = new AtomicLong();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                h.set(orgApi.submit(OrgType.FACTORY, name, code, rep)));
        orgApi.approve(h.get(), ops.userId());
        return new Org(h.get(), rep);
    }

    @Test
    void 两家单位的余额互不影响() {
        Org a = org("13004000001", "110101199001110001", "甲厂", "9111000000000l01X");
        Org b = org("13004000002", "110101199001110002", "乙厂", "9111000000000l02X");

        fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 100_000, "甲厂充值", "k-a-1", a.rep());

        assertThat(fundApi.orgBalanceOf(a.orgId(), AccountType.USER_FUNDS, a.rep()))
                .isEqualTo(100_000);
        // **乙厂一分钱都不该多。**共用一个余额的话这里会是 100000 ——
        // 而那意味着乙厂能拿甲厂的钱去发薪
        assertThat(fundApi.orgBalanceOf(b.orgId(), AccountType.USER_FUNDS, b.rep()))
                .isZero();
    }

    @Test
    void 别家的法人动不了我的账户() {
        Org a = org("13004000003", "110101199001110003", "被动厂", "9111000000000l03X");
        Org b = org("13004000004", "110101199001110004", "外人厂", "9111000000000l04X");

        // 往别人账户里打钱看着像做好事,但那笔钱随后会被用来发薪,
        // 等于替别人承担了用工责任
        assertThatThrownBy(() ->
                fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 1000, "越权充值", "k-x-1", b.rep()))
                .hasMessageContaining("法人代表");

        // 余额是经营信息,别家看不到(铁律 5.1)
        assertThatThrownBy(() ->
                fundApi.orgBalanceOf(a.orgId(), AccountType.USER_FUNDS, b.rep()))
                .hasMessageContaining("法人代表");
    }

    @Test
    void 平台运维能看也能充_平台账户只有平台能动() {
        Org a = org("13004000005", "110101199001110005", "平台可管厂", "9111000000000l05X");

        fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 5_000, "平台代充", "k-p-1", ops.userId());
        assertThat(fundApi.orgBalanceOf(a.orgId(), AccountType.USER_FUNDS, ops.userId()))
                .isEqualTo(5_000);

        // 平台自己的账户(orgId 为 null)只有平台运维能动
        assertThatThrownBy(() ->
                fundApi.topUpOrg(null, AccountType.USER_FUNDS, 1000, "越权", "k-p-2", a.rep()))
                .hasMessageContaining("平台运维");
    }

    @Test
    void 充值必须带幂等键() {
        Org a = org("13004000006", "110101199001110006", "幂等厂", "9111000000000l06X");
        // 充值重发一次就多一笔钱。幂等键是唯一能拦住的东西
        assertThatThrownBy(() ->
                fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 1000, "无键", "  ", a.rep()))
                .hasMessageContaining("幂等键");
    }

    @Test
    void 同一个幂等键充两次只加一次() {
        Org a = org("13004000007", "110101199001110007", "重发厂", "9111000000000l07X");
        fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 2_000, "首次", "k-dup-1", a.rep());
        fundApi.topUpOrg(a.orgId(), AccountType.USER_FUNDS, 2_000, "重发", "k-dup-1", a.rep());
        assertThat(fundApi.orgBalanceOf(a.orgId(), AccountType.USER_FUNDS, a.rep()))
                .as("重发不该让余额翻倍")
                .isEqualTo(2_000);
    }

    // ─────────────── 借支归属 ───────────────

    @Test
    void 借支只能由本单位法人或平台批() {
        Org a = org("13004000008", "110101199001110008", "借支厂", "9111000000000l08X");
        Org b = org("13004000009", "110101199001110009", "外人借支厂", "9111000000000l09X");
        long worker = verified("13004000010", "工人", "110101199001110010");

        // 替别家批借支,等于替别家承诺了一笔要从工资里扣回来的钱
        assertThatThrownBy(() ->
                advanceApi.grantAdvance(a.orgId(), worker, 10_000, "越权批", b.rep()))
                .hasMessageContaining("法人代表");

        // 本单位法人可以
        long id = advanceApi.grantAdvance(a.orgId(), worker, 10_000, "预支生活费", a.rep());
        assertThat(id).isPositive();
    }

    @Test
    void 平台自己垫那条仍然只有平台运维能走() {
        Org a = org("13004000011", "110101199001110011", "平台垫厂", "9111000000000l10X");
        long worker = verified("13004000012", "工人乙", "110101199001110012");
        assertThatThrownBy(() ->
                advanceApi.grantAdvance(null, worker, 10_000, "越权垫", a.rep()))
                .hasMessageContaining("平台运维");
    }

    // ─────────────── 机构端的列表 ───────────────

    @Test
    void 机构只看得到自己那家的代发单和借支() {
        Org a = org("13004000013", "110101199001110013", "列表甲厂", "9111000000000l11X");
        Org b = org("13004000014", "110101199001110014", "列表乙厂", "9111000000000l12X");
        long worker = verified("13004000015", "工人丙", "110101199001110015");
        advanceApi.grantAdvance(a.orgId(), worker, 5_000, "甲厂预支", a.rep());

        assertThat(advanceApi.listByOrg(a.orgId(), a.rep())).hasSize(1);
        // 借支是谁欠了这家多少钱,别家看得到等于把用工成本公开了(铁律 5.1)。
        // **列表接口挡住路人时回空列表,不是抛异常** —— 那是这个项目定下的口径
        assertThat(advanceApi.listByOrg(a.orgId(), b.rep())).isEmpty();
        // 挡住路人不能连平台运维一起挡掉
        assertThat(advanceApi.listByOrg(a.orgId(), ops.userId())).hasSize(1);

        assertThat(fundApi.listByOrg(a.orgId(), b.rep())).isEmpty();
    }
}
