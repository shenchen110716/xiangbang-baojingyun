package com.xbb.talent.internal;

import com.xbb.engagement.api.EngagementCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 履约完成沉淀进人才档案。"干过并且干完了"是人才库最有价值的一条信息——
 * 它把人才库和单纯的画像区分开来。
 */
@Component("talentEngagementEventListener")
class EngagementEventListener {

    private final TalentProfileRepository profiles;
    private final CountedEngagementRepository counted;

    EngagementEventListener(TalentProfileRepository profiles, CountedEngagementRepository counted) {
        this.profiles = profiles;
        this.counted = counted;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "talentTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        // 投递语义是至少一次,而这里是累加——同一单数两次,人才库排序就被刷高了。
        // 按履约单去重,不是按事件 id:同一单即便换个 eventId 重发也只该计一次。
        if (counted.existsById(event.applicationId())) {
            return;
        }
        counted.save(new CountedEngagement(event.applicationId(), event.workerUserId()));

        TalentProfile profile = profiles.findById(event.workerUserId())
                .orElseGet(() -> new TalentProfile(event.workerUserId()));
        profile.recordEngagementCompleted(event.occurredAt());
        profiles.save(profile);
    }
}
