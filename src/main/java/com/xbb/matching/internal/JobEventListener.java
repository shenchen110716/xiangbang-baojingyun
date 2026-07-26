package com.xbb.matching.internal;

import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
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

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        JobProjection projection = jobs.findById(event.jobId())
                .orElseGet(() -> new JobProjection(event.jobId(), event.orgId(), event.wageCents()));
        projection.updateBasics(event.orgId(), event.wageCents());
        jobs.save(projection);
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobClosed event) {
        // 投影还没建就没什么可关的;不新建"已关闭"的空投影,那会被随后的 JobPosted 覆盖回 open
        jobs.findById(event.jobId()).ifPresent(job -> {
            job.close();
            jobs.save(job);
        });
    }
}
