package com.xbb.org.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import com.xbb.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrgOutboxRelay extends AbstractOutboxRelay<OrgOutboxEvent> {

    private final OrgOutboxRepository outbox;

    OrgOutboxRelay(OrgOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected OutboxEventRepository<OrgOutboxEvent> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。需要"由测试自己驱动投递"的用例
    // 只关掉它关心的那一个中继——全关掉的话连搭建前置数据都跑不动了。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.org.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("orgTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
