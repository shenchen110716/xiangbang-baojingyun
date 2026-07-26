package com.xbb.fund.api;

/**
 * 担保策略(§8"把风险变成接缝")。
 *
 * <p>"v1.0 沿用现有押金模式(业务决定),但它**不进核心链路**,只作为策略实现
 * 挂在接口后。监管收紧时换实现即可,**资金域与结算域一行不动**。"
 *
 * <p>§8.2 核心原则:**策略只做决策,不碰钱**。纯函数 → 可测试、可回放、可 A/B,
 * 换策略等于换一个纯函数,账本不会被绕过。
 */
public interface GuaranteePolicy {

    GuaranteeDecision decide(GuaranteeContext ctx);
}
