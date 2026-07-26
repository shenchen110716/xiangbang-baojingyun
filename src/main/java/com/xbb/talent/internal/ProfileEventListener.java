package com.xbb.talent.internal;

import com.xbb.profile.api.ProfileUpdated;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/** §4.2:"人才库 | 档案沉淀复用 | **订阅画像事件**"。 */
@Component("talentProfileEventListener")
class ProfileEventListener {

    private final TalentProfileRepository profiles;

    ProfileEventListener(TalentProfileRepository profiles) {
        this.profiles = profiles;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "talentTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ProfileUpdated event) {
        Map<String, Double> tags = new LinkedHashMap<>();
        event.tags().forEach(t -> tags.put(t.tagName(), t.confidence()));
        TalentProfile profile = profiles.findById(event.userId())
                .orElseGet(() -> new TalentProfile(event.userId()));
        profile.updateProfile(tags, event.expectedWageCents());
        profiles.save(profile);
    }
}
