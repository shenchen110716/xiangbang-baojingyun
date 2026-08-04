package com.xbb.settlement.internal;

import jakarta.persistence.*;

/**
 * 方案下的调整项:奖励 / 扣款 / 罚款。
 *
 * <p>**金额一律存正数,方向由类型决定。** 让"负数的扣款"和"正数的扣款"两种写法并存,
 * 迟早有人加错符号 —— 而加错符号的后果是多发或少发工资。
 */
@Entity
@Table(name = "pay_plan_factor", schema = "settlement")
public class PayPlanFactor {

    public enum FactorType { BONUS, DEDUCTION, PENALTY }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private long planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_type", nullable = false, length = 16)
    private FactorType factorType;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    protected PayPlanFactor() { }

    PayPlanFactor(long planId, FactorType factorType, String name, long amountCents) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("调整项金额必须为正数,方向由类型决定");
        }
        this.planId = planId;
        this.factorType = factorType;
        this.name = name;
        this.amountCents = amountCents;
    }

    public Long getId() { return id; }
    public long getPlanId() { return planId; }
    public FactorType getFactorType() { return factorType; }
    public String getName() { return name; }
    public long getAmountCents() { return amountCents; }
}
