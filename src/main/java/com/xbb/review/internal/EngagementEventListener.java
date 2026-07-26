package com.xbb.review.internal;

import com.xbb.engagement.api.EngagementCompleted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/** 枢纽事件 EngagementCompleted 的三个消费方之一:开启双盲评价窗(§9.3)。 */
@Component("reviewEngagementEventListener")
class EngagementEventListener {

    private final CompletedEngagementRepository completedEngagements;
    private final ReviewService reviewService;

    EngagementEventListener(CompletedEngagementRepository completedEngagements, ReviewService reviewService) {
        this.completedEngagements = completedEngagements;
        this.reviewService = reviewService;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "reviewTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        completedEngagements.save(new CompletedEngagement(
                event.applicationId(), event.jobId(), event.workerUserId(),
                event.orgId(), event.occurredAt()));
        // 履约率变了,信用分要重算
        reviewService.recalculateCredit(event.workerUserId(), "履约完成");
    }
}
