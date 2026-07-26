package com.xbb.talent.internal;

import com.xbb.engagement.api.EngagementCompleted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 履约完成沉淀进人才档案。"干过并且干完了"是人才库最有价值的一条信息——
 * 它把人才库和单纯的画像区分开来。
 */
@Component("talentEngagementEventListener")
class EngagementEventListener {

    private final TalentProfileRepository profiles;

    EngagementEventListener(TalentProfileRepository profiles) {
        this.profiles = profiles;
    }

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "talentTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        TalentProfile profile = profiles.findById(event.workerUserId())
                .orElseGet(() -> new TalentProfile(event.workerUserId()));
        profile.recordEngagementCompleted(event.occurredAt());
        profiles.save(profile);
    }
}
