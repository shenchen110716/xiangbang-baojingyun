package com.xbb.fund.api;

/**
 * §8.1 接口契约。reason 必须可读——决策要能对用户解释,
 * 不能只甩一个金额(蓝领对黑盒规则的信任度极低)。
 */
public record GuaranteeDecision(boolean required, long amountCents, String reason) { }
