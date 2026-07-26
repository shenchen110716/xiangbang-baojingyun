package com.xbb.fund.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FundOutboxRelay extends AbstractOutboxRelay<FundOutboxEvent> {

    private final FundOutboxRepository outbox;

    FundOutboxRelay(FundOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected JpaRepository<FundOutboxEvent, Long> outbox() {
        return outbox;
    }

    @Scheduled(fixedDelayString = "${xbb.outbox.relay.interval-ms:5000}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("fundTransactionManager")
    public int publishPending() {
        return relayPending(outbox.findByStatusInOrderByIdAsc(retryable()));
    }
}
