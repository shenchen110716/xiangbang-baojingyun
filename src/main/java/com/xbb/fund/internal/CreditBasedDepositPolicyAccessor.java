package com.xbb.fund.internal;

import com.xbb.fund.api.GuaranteeContext;
import com.xbb.fund.api.GuaranteeDecision;

/**
 * 测试辅助:策略实现是包私有的(不该被别的域直接 new),
 * 但它是纯函数,值得用纯单测覆盖分段边界。这里开一个同包的薄壳给测试用。
 */
public class CreditBasedDepositPolicyAccessor {

    private final CreditBasedDepositPolicy delegate = new CreditBasedDepositPolicy();

    public GuaranteeDecision decide(GuaranteeContext ctx) {
        return delegate.decide(ctx);
    }
}
