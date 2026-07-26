package com.xbb.broker.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import com.xbb.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BrokerOutboxRelay extends AbstractOutboxRelay<BrokerOutboxEvent> {

    private final BrokerOutboxRepository outbox;

    BrokerOutboxRelay(BrokerOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected OutboxEventRepository<BrokerOutboxEvent> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.broker.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("brokerTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
