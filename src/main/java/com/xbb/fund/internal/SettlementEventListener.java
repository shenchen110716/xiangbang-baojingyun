package com.xbb.fund.internal;

import com.xbb.settlement.api.SettlementCalculated;
import com.xbb.settlement.api.SettlementVoided;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:broker 域也有个同名类 SettlementEventListener,默认 bean 名会撞车
@Component("fundSettlementEventListener")
class SettlementEventListener {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SettlementEventListener.class);

    private final PayoutRepository payouts;
    private final AdvanceService advances;

    SettlementEventListener(PayoutRepository payouts, AdvanceService advances) {
        this.payouts = payouts;
        this.advances = advances;
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
        // 中继是至少一次投递,同一事件会重复到达。
        //
        // **先查后插在并发下是无效的**:多实例(或多个测试上下文)的中继同时投递同一条
        // SettlementCalculated 时,两边都查到"不存在",然后都插入,一个撞
        // settlement_id UNIQUE。这正是铁律 4 里写过的那条——去重要让数据库裁决,
        // 不能靠"查一下再写"。
        //
        // 先查一次仍然保留:绝大多数重复投递不是并发的,查一次能省掉一次异常。
        // 但真撞上了就把它当成"已经有人建好了",而不是让事件卡在 FAILED 永远重试。
        if (payouts.findBySettlementId(event.settlementId()).isPresent()) {
            return;
        }
        try {
            // **借支抵扣在这里,和建单同一个事务。**分成两步的话,中间崩掉就会出现
            // "扣了但没建单"(钱扣了工资单没了)或"建了单没扣"(白发一笔),
            // 而这两种都要人工对账才看得出来。
            //
            // 结算域算的是应发,它不知道这人欠平台多少 —— 那是资金域的事,
            // 也是这个抵扣只能放在这里的原因(结算域反向依赖资金域会成环)。
            long gross = event.amountCents();
            long deducted = advances.deductFromSalary(event.workerUserId(), event.settlementId(), gross);
            // orgId 可能为 null:老载荷重放、或岗位副本还没到。
            // 那时代发退回平台账户 —— 和按单位分账之前的行为一致
            payouts.save(new Payout(event.settlementId(), event.workerUserId(),
                    gross - deducted, event.orgId()));
        } catch (DataIntegrityViolationException e) {
            // **只在确认记录真的已存在时才吞。**
            //
            // 不能见到 DataIntegrityViolation 就当成"重复"——那会把真实的写库失败
            // 也一并吞掉,事件被标 PUBLISHED、工资单永远不生成,而且无声无息。
            // (我第一版就是这么写的,被"真实消费方写库失败时事件也必须留在表里可重试"
            //  这条测试当场抓住。)
            //
            // 再查一次:查得到 = 并发的另一次投递赢了,目的已达成;
            // 查不到 = 是别的写库问题,必须抛出去让中继重试。
            if (payouts.findBySettlementId(event.settlementId()).isEmpty()) {
                throw e;
            }
            log.info("待发放记录已由并发的另一次投递创建,跳过。settlementId={}", event.settlementId());
        }
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementVoided event) {
        // **找不到待发放记录时必须抛出,不能静默返回。**
        // 同一批里 SettlementCalculated 可能因为下游抖动被退避、排在 Voided 后面重投;
        // 那时这条 Voided 若被静默吞掉(中继随即标 PUBLISHED、永不重投),
        // 稍后 Calculated 重投成功会建出一张 PENDING 的工资单——
        // 一笔已作废的结算留下了可发放的单子,运营一点就真把钱打出去了。
        // 抛出去让中继退避重试,等 Calculated 先落地。
        Payout payout = payouts.findBySettlementId(event.settlementId())
                .orElseThrow(() -> new IllegalStateException(
                        "结算 " + event.settlementId() + " 的待发放记录尚未到达,稍后重试"));
        payout.cancel();
        payouts.save(payout);
    }
}
