package com.xbb.fund.internal;

import com.xbb.review.api.CreditScoreChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 担保决策要用信用分。方向 fund → review,review 不依赖 fund,无环。
 */
@Component("fundReviewEventListener")
class ReviewEventListener {

    private final WorkerCreditRepository credits;

    ReviewEventListener(WorkerCreditRepository credits) {
        this.credits = credits;
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "fundTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CreditScoreChanged event) {
        WorkerCredit credit = credits.findById(event.userId())
                .orElseGet(() -> new WorkerCredit(event.userId(), event.newScore()));
        credit.update(event.newScore());
        credits.save(credit);
    }
}
