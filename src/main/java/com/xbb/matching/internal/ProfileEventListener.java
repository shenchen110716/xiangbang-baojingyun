package com.xbb.matching.internal;

import com.xbb.profile.api.JobProfileUpdated;
import com.xbb.profile.api.ProfileUpdated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component("matchingProfileEventListener")
class ProfileEventListener {

    private final WorkerProjectionRepository workers;
    private final JobProjectionRepository jobs;

    ProfileEventListener(WorkerProjectionRepository workers, JobProjectionRepository jobs) {
        this.workers = workers;
        this.jobs = jobs;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ProfileUpdated event) {
        Map<String, Double> tags = new LinkedHashMap<>();
        event.tags().forEach(t -> tags.put(t.tagName(), t.confidence()));
        WorkerProjection projection = workers.findById(event.userId())
                .orElseGet(() -> new WorkerProjection(event.userId(), Map.of(), null, null, null));
        projection.update(tags, event.expectedWageCents(), event.lat(), event.lon());
        workers.save(projection);
    }

    /**
     * 岗位画像先于岗位发布事件到达是不可能的(必须先有岗位才能设画像),
     * 但投影行可能还没建——用 orgId=0/wage=0 占位建行,随后的 JobPosted 会补齐。
     */
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobProfileUpdated event) {
        JobProjection projection = jobs.findById(event.jobId())
                .orElseGet(() -> new JobProjection(event.jobId(), 0L, 0L));
        projection.updateProfile(event.mustTags(), event.niceTags(), event.lat(), event.lon());
        jobs.save(projection);
    }
}
