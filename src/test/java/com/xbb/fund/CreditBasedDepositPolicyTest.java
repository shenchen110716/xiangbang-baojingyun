package com.xbb.fund;

import com.xbb.fund.api.GuaranteeContext;
import com.xbb.fund.api.GuaranteeDecision;
import com.xbb.fund.internal.CreditBasedDepositPolicyAccessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 担保策略是纯函数(§8.2),纯 JUnit 单测。分段照 §5.3.3。
 * 岗位薪资统一用 40000 分(400 元),全额 = 50% = 20000 分。
 */
class CreditBasedDepositPolicyTest {

    private final CreditBasedDepositPolicyAccessor policy = new CreditBasedDepositPolicyAccessor();

    private GuaranteeDecision decideWithCredit(int creditScore) {
        return policy.decide(new GuaranteeContext(1L, 2L, creditScore, 40_000, 0));
    }

    @Test
    void 信用80以上免押() {
        GuaranteeDecision d = decideWithCredit(85);

        assertThat(d.required()).isFalse();
        assertThat(d.amountCents()).isZero();
        assertThat(d.reason()).contains("免押");
    }

    @Test
    void 信用60到79收半额() {
        GuaranteeDecision d = decideWithCredit(70);

        assertThat(d.required()).isTrue();
        assertThat(d.amountCents()).isEqualTo(10_000);
        assertThat(d.reason()).contains("半额");
    }

    @Test
    void 信用40到59收全额() {
        GuaranteeDecision d = decideWithCredit(50);

        assertThat(d.required()).isTrue();
        assertThat(d.amountCents()).isEqualTo(20_000);
        assertThat(d.reason()).contains("全额");
    }

    @Test
    void 信用40以下全额且提示受限() {
        GuaranteeDecision d = decideWithCredit(30);

        assertThat(d.required()).isTrue();
        assertThat(d.amountCents()).isEqualTo(20_000);
        assertThat(d.reason()).contains("受限");
    }

    @Test
    void 分段边界落在正确的档() {
        assertThat(decideWithCredit(80).required()).isFalse();      // 80 免押
        assertThat(decideWithCredit(79).amountCents()).isEqualTo(10_000);  // 79 半额
        assertThat(decideWithCredit(60).amountCents()).isEqualTo(10_000);  // 60 半额
        assertThat(decideWithCredit(59).amountCents()).isEqualTo(20_000);  // 59 全额
        assertThat(decideWithCredit(40).amountCents()).isEqualTo(20_000);  // 40 全额
        assertThat(decideWithCredit(39).reason()).contains("受限");        // 39 受限
    }

    @Test
    void 新人60分落在半额而不是受限档() {
        // "0 分等于把新人判死刑"的同类考虑:新人不该一上来就被最严档卡住
        GuaranteeDecision d = decideWithCredit(60);

        assertThat(d.required()).isTrue();
        assertThat(d.amountCents()).isEqualTo(10_000);
        assertThat(d.reason()).doesNotContain("受限");
    }

    @Test
    void 理由必须可读能对用户解释() {
        // 蓝领对黑盒规则信任度极低,决策不能只甩一个金额
        for (int credit : new int[]{85, 70, 50, 30}) {
            assertThat(decideWithCredit(credit).reason())
                    .isNotBlank()
                    .contains(String.valueOf(credit));
        }
    }

    @Test
    void 押金金额随岗位薪资变化() {
        GuaranteeDecision small = policy.decide(new GuaranteeContext(1L, 2L, 50, 20_000, 0));
        GuaranteeDecision big = policy.decide(new GuaranteeContext(1L, 2L, 50, 80_000, 0));

        assertThat(big.amountCents()).isGreaterThan(small.amountCents());
    }
}
