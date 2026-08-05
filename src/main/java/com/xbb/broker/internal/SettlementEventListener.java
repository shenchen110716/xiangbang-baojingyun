package com.xbb.broker.internal;

import com.xbb.settlement.api.SettlementCalculated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式命名:fund 域也有同名类,默认 bean 名会撞车。 */
@Component("brokerSettlementEventListener")
class SettlementEventListener {

    private final CommissionBaseRepository bases;
    private final ShareUpgradeService upgrades;

    SettlementEventListener(CommissionBaseRepository bases, ShareUpgradeService upgrades) {
        this.upgrades = upgrades;
        this.bases = bases;
    }

    /**
     * 记下这笔结算的佣金基数,等钱真的付了(FundsDisbursed)再分账。
     *
     * <p>为什么要落副本而不是发放时回查:两个事件的到达顺序不保证,
     * 而且跨域回查会被铁律 1 在数据库层挡住。
     *
     * <p>commissionBaseCents 是后加的字段,更早落库的载荷里没有,重放时为 0 ——
     * 那种情况退回用 amountCents,否则旧事件重放会把佣金基数记成 0。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementCalculated event) {
        long base = event.commissionBaseCents() > 0 ? event.commissionBaseCents() : event.amountCents();
        // 主键相同时 save 走 merge,对至少一次投递是幂等的
        bases.save(new CommissionBase(event.settlementId(), base));

        // 成交了:如果这个工人是分享来的,看要不要让分享人升级为业务员。
        // 幂等由 share_conversion 的状态保证(已计数的不再计) ——
        // 中继会重复投递,不挡的话一单能顶好几单
        upgrades.onDeal(event.workerUserId(), event.settlementId(), false);
    }
}
