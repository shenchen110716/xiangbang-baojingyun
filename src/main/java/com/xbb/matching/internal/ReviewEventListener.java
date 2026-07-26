package com.xbb.matching.internal;

import com.xbb.review.api.CreditScoreChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 评价域"只发布'信用分已变更'事件"(§4.2),匹配域订阅后落到自己的只读投影——
 * 不去查评价域的表。
 */
@Component("matchingReviewEventListener")
class ReviewEventListener {

    private final WorkerProjectionRepository workers;

    ReviewEventListener(WorkerProjectionRepository workers) {
        this.workers = workers;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CreditScoreChanged event) {
        WorkerProjection projection = workers.findById(event.userId())
                .orElseGet(() -> new WorkerProjection(event.userId(), Map.of(), null, null, null));
        projection.updateCreditScore(event.newScore());
        workers.save(projection);
    }
}
