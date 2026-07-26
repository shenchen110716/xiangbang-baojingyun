package com.xbb.fund.internal;

import com.xbb.review.api.CreditScoreChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 担保决策要用信用分。方向 fund → review,review 不依赖 fund,无环。
 */
@Component("fundReviewEventListener")
class ReviewEventListener {

    private final WorkerCreditRepository credits;

    ReviewEventListener(WorkerCreditRepository credits) {
        this.credits = credits;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CreditScoreChanged event) {
        WorkerCredit credit = credits.findById(event.userId())
                .orElseGet(() -> new WorkerCredit(event.userId(), event.newScore()));
        credit.update(event.newScore());
        credits.save(credit);
    }
}
