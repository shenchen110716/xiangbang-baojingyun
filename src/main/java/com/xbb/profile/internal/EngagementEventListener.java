package com.xbb.profile.internal;

import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.profile.api.ProfileUpdated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 履约反哺画像(主文档 §5.2,"这个模块的灵魂"):
 * "他说他会,不算数;他干过并且评价好,才算数。画像越用越准,
 * 这是匹配中枢真正的燃料来源。"
 *
 * <p>枢纽事件 EngagementCompleted 的三个消费方之一(§9.3)。
 */
@Component("profileEngagementEventListener")
class EngagementEventListener {

    private final ProfileTagRepository tags;
    private final JobProfileRepository jobProfiles;
    private final WorkerPreferenceRepository workerPreferences;
    private final ApplicationEventPublisher events;

    EngagementEventListener(ProfileTagRepository tags, JobProfileRepository jobProfiles,
                             WorkerPreferenceRepository workerPreferences,
                             ApplicationEventPublisher events) {
        this.tags = tags;
        this.jobProfiles = jobProfiles;
        this.workerPreferences = workerPreferences;
        this.events = events;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "profileTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        JobProfile jobProfile = jobProfiles.findById(event.jobId()).orElse(null);
        if (jobProfile == null) return;   // 岗位没设过画像,没有可验证的标签

        // 只升级**相交**的部分:他去干了叉车岗,能证明的是"叉车",
        // 不能证明他自称的"电工"。
        Set<String> jobTags = new HashSet<>(jobProfile.getMustTags());
        jobTags.addAll(jobProfile.getNiceTags());
        if (jobTags.isEmpty()) return;

        List<ProfileTag> workerTags = tags.findByUserId(event.workerUserId());
        boolean changed = false;
        for (ProfileTag tag : workerTags) {
            if (jobTags.contains(tag.getTagName()) && tag.getSource() != ProfileTag.Source.ENGAGEMENT_VERIFIED) {
                tag.markEngagementVerified();
                tags.save(tag);
                changed = true;
            }
        }
        if (changed) {
            publishProfileUpdated(event.workerUserId());
        }
    }

    /**
     * 置信度变了,下游(匹配域投影)要知道,否则技能维度还在用旧的 0.4 算。
     *
     * <p>载荷必须带上**当前的**期望薪资与坐标:消费方是整行覆盖投影的,
     * 这里传 null 会把工人已经填过的偏好静默清空。
     */
    private void publishProfileUpdated(long userId) {
        List<ProfileUpdated.TagUpdate> updates = tags.findByUserId(userId).stream()
                .map(t -> new ProfileUpdated.TagUpdate(t.getTagName(), t.getSource().name(), t.getConfidence()))
                .toList();
        WorkerPreference preference = workerPreferences.findById(userId).orElse(null);
        events.publishEvent(new ProfileUpdated(
                userId, updates,
                preference == null ? null : preference.getExpectedWageCents(),
                preference == null ? null : preference.getLat(),
                preference == null ? null : preference.getLon(),
                Instant.now()));
    }
}
