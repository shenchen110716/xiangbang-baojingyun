package com.xbb.mall.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import com.xbb.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MallOutboxRelay extends AbstractOutboxRelay<MallOutboxEvent> {

    private final MallOutboxRepository outbox;

    MallOutboxRelay(MallOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected OutboxEventRepository<MallOutboxEvent> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.mall.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("mallTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
