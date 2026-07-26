package com.xbb.talent.internal;

import com.xbb.profile.api.ProfileUpdated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;


/** §4.2:"人才库 | 档案沉淀复用 | **订阅画像事件**"。 */
@Component("talentProfileEventListener")
class ProfileEventListener {

    private final TalentProfileRepository profiles;

    ProfileEventListener(TalentProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
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
