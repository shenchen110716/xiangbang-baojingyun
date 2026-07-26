package com.xbb.profile.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProfileOutboxRelay extends AbstractOutboxRelay<ProfileOutboxEvent> {

    private final ProfileOutboxRepository outbox;

    ProfileOutboxRelay(ProfileOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected JpaRepository<ProfileOutboxEvent, Long> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.profile.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("profileTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
