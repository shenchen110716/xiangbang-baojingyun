package com.xbb.fund.internal;

import com.xbb.settlement.api.SettlementCalculated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:broker 域也有个同名类 SettlementEventListener,默认 bean 名会撞车
@Component("fundSettlementEventListener")
class SettlementEventListener {

    private final PayoutRepository payouts;

    SettlementEventListener(PayoutRepository payouts) {
        this.payouts = payouts;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementCalculated event) {
        payouts.save(new Payout(event.settlementId(), event.workerUserId(), event.amountCents()));
    }
}
