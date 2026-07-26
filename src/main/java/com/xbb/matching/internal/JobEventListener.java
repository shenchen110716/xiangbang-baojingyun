package com.xbb.matching.internal;

import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component("matchingJobEventListener")
class JobEventListener {

    private final JobProjectionRepository jobs;

    JobEventListener(JobProjectionRepository jobs) {
        this.jobs = jobs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        JobProjection projection = jobs.findById(event.jobId())
                .orElseGet(() -> new JobProjection(event.jobId(), event.orgId(), event.wageCents()));
        projection.updateBasics(event.orgId(), event.wageCents());
        jobs.save(projection);
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobClosed event) {
        // 投影还没建就没什么可关的;不新建"已关闭"的空投影,那会被随后的 JobPosted 覆盖回 open
        jobs.findById(event.jobId()).ifPresent(job -> {
            job.close();
            jobs.save(job);
        });
    }
}
