package com.xbb.matching.internal;

import com.xbb.profile.api.JobProfileUpdated;
import com.xbb.profile.api.ProfileUpdated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;


@Component("matchingProfileEventListener")
class ProfileEventListener {

    private final WorkerProjectionRepository workers;
    private final JobProjectionRepository jobs;

    ProfileEventListener(WorkerProjectionRepository workers, JobProjectionRepository jobs) {
        this.workers = workers;
        this.jobs = jobs;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
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
    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "matchingTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(JobProfileUpdated event) {
        JobProjection projection = jobs.findById(event.jobId())
                .orElseGet(() -> new JobProjection(event.jobId(), 0L, 0L));
        projection.updateProfile(event.mustTags(), event.niceTags(), event.lat(), event.lon());
        jobs.save(projection);
    }
}
