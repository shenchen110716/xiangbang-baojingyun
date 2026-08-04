package com.xbb.attendance.internal;

import com.xbb.engagement.api.ApplicationAccepted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 显式命名:其它域也有同名类,默认 bean 名会撞车。 */
@Component("attendanceEngagementEventListener")
class EngagementEventListener {

    private final EngagedWorkerRepository engaged;

    EngagementEventListener(EngagedWorkerRepository engaged) {
        this.engaged = engaged;
    }

    /**
     * 录用之后这个人才可能有考勤。
     *
     * <p>`@EventListener` 而非 AFTER_COMMIT:事件由履约域的 outbox 中继投递,
     * AFTER_COMMIT 要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "attendanceTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ApplicationAccepted event) {
        // 至少一次投递:主键相同时 save 走 merge,是幂等的
        engaged.save(new EngagedWorker(event.applicationId(), event.jobId(),
                event.applicantUserId(), event.orgId(), event.occurredAt()));
    }
}
