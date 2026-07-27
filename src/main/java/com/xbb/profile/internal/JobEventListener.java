package com.xbb.profile.internal;

import com.xbb.job.api.JobPosted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 落地"岗位属于哪个组织",供岗位画像的归属校验使用。 */
@Component("profileJobEventListener")
class JobEventListener {

    private final PostedJobRefRepository postedJobs;

    JobEventListener(PostedJobRefRepository postedJobs) {
        this.postedJobs = postedJobs;
    }

    @EventListener
    @Transactional(transactionManager = "profileTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobPosted event) {
        // 有则更新、无则新建:重投的 JobPosted 不该改变已有归属
        postedJobs.save(new PostedJobRef(event.jobId(), event.orgId()));
    }
}
