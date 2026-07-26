package com.xbb.matching.internal;

import com.xbb.review.api.CreditScoreChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;


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

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CreditScoreChanged event) {
        WorkerProjection projection = workers.findById(event.userId())
                .orElseGet(() -> new WorkerProjection(event.userId(), Map.of(), null, null, null));
        projection.updateCreditScore(event.newScore());
        workers.save(projection);
    }
}
