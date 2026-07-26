package com.xbb.engagement.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import com.xbb.OutboxEventRepository;
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
    protected OutboxEventRepository<EngagementOutboxEvent> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。需要"由测试自己驱动投递"的用例
    // 只关掉它关心的那一个中继——全关掉的话连搭建前置数据都跑不动了。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.engagement.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("engagementTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
