package com.xbb.engagement.internal;

import com.xbb.job.api.JobClosed;
import com.xbb.job.api.JobPosted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:settlement 域也有个同名类 JobEventListener,默认 bean 名会撞车
@Component("engagementJobEventListener")
class JobEventListener {

    private final PostedJobRepository postedJobs;

    JobEventListener(PostedJobRepository postedJobs) {
        this.postedJobs = postedJobs;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        postedJobs.save(new PostedJob(event.jobId(), event.orgId(), event.wageCents()));
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobClosed event) {
        // 岗位可能还没投影过来(事件乱序/投影丢失),那就没什么可关的——
        // 不新建一条"已关闭"的空投影,那会让后续 JobPosted 覆盖成 open。
        postedJobs.findById(event.jobId()).ifPresent(job -> {
            job.close();
            postedJobs.save(job);
        });
    }
}
