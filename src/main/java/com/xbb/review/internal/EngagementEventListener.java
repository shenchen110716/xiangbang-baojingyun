package com.xbb.review.internal;

import com.xbb.engagement.api.EngagementCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


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
    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "reviewTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        completedEngagements.save(new CompletedEngagement(
                event.applicationId(), event.jobId(), event.workerUserId(),
                event.orgId(), event.occurredAt()));
        // 履约率变了,信用分要重算
        reviewService.recalculateCredit(event.workerUserId(), "履约完成");
    }
}
