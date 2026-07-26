package com.xbb.fund.internal;

import com.xbb.settlement.api.SettlementCalculated;
import com.xbb.settlement.api.SettlementVoided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:broker 域也有个同名类 SettlementEventListener,默认 bean 名会撞车
@Component("fundSettlementEventListener")
class SettlementEventListener {

    private final PayoutRepository payouts;

    SettlementEventListener(PayoutRepository payouts) {
        this.payouts = payouts;
    }

    /**
     * 这里**故意不用** {@code @TransactionalEventListener(AFTER_COMMIT)}:
     * 该事件现在由 {@code SettlementOutboxRelay} 在自己的事务里投递,
     * 用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑——那时 outbox 行已被标记 PUBLISHED,
     * 这里再抛异常事件就永久丢了,outbox 等于没做。
     * 用 {@code @EventListener} 同步执行,异常才能回到中继的重试逻辑里。
     * 「提交后才投递」这个语义已经由"事件是从已提交的 outbox 行里读出来的"保证了。
     */
    @EventListener
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementCalculated event) {
        // 中继是至少一次投递,同一事件会重复到达。表上 settlement_id UNIQUE 已经挡住了第二笔钱,
        // 但仅靠它会让中继撞约束、永远重试,事件卡在 FAILED。这里显式吸收重复,重试才能收敛。
        if (payouts.findBySettlementId(event.settlementId()).isPresent()) {
            return;
        }
        payouts.save(new Payout(event.settlementId(), event.workerUserId(), event.amountCents()));
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementVoided event) {
        payouts.findBySettlementId(event.settlementId()).ifPresent(payout -> {
            payout.cancel();
            payouts.save(payout);
        });
    }
}
