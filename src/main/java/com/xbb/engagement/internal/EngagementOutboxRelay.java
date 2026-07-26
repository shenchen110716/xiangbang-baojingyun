package com.xbb.engagement.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EngagementOutboxRelay extends AbstractOutboxRelay<EngagementOutboxEvent> {

    private final EngagementOutboxRepository outbox;

    EngagementOutboxRelay(EngagementOutboxRepository outbox, ApplicationEventPublisher events,
                           ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected JpaRepository<EngagementOutboxEvent, Long> outbox() {
        return outbox;
    }

    @Scheduled(fixedDelayString = "${xbb.outbox.relay.interval-ms:5000}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("engagementTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
