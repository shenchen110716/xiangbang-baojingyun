package com.xbb.settlement.api;

import java.time.Instant;

/*
 * orgId 是 2026-08-06 加的:资金账户改为按单位分账之后,
 * 代发要知道**钱从哪个单位的账户出**。不带的话资金域只能扣平台账户,
 * 而那正是改动前的行为 —— 于是"改了"和"没改"在结果上一模一样。
 */

/**
 * 结算已算出金额,可以准备发放了——不代表钱已经动。
 * 真正发钱的操作在 fund 域(资金域是唯一动钱者)。
 *
 * <p>{@code commissionBaseCents} 是**佣金分账的基数**,不等于 amountCents:
 * 老系统 JobComputerService 拿浮动工资(floatSalary)算佣金,不是拿应发总额。
 * 有计薪方案时它是方案里的浮动部分;没有方案时退回等于应发总额(旧行为)。
 *
 * <p>兼容性:这个字段是后加的,更早落库的 outbox 载荷里没有,重放时为 0。
 * 消费方见到 0 要退回用 amountCents —— 否则那批旧事件重放时佣金全部变成 0。
 */
public record SettlementCalculated(long settlementId, long applicationId, long workerUserId,
                                    long amountCents, long commissionBaseCents,
                                    Long orgId, Instant occurredAt) {

    /**
     * 老的六参构造。<b>加之前已经落库的 outbox 载荷里没有 orgId</b>,
     * 重放时 Jackson 给 null —— 消费方必须容忍(和 OrganizationApproved 那次一样)。
     */
    public SettlementCalculated(long settlementId, long applicationId, long workerUserId,
                                long amountCents, long commissionBaseCents, Instant occurredAt) {
        this(settlementId, applicationId, workerUserId, amountCents, commissionBaseCents, null, occurredAt);
    }
}
