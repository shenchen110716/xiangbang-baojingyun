package com.xbb.settlement.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算域的 outbox 中继。投递逻辑在 {@link AbstractOutboxRelay},这里只补
 * 事务管理器与调度——两者都要求编译期常量,没法在基类里参数化。
 *
 * <p>生产由调度驱动;测试里把间隔调到极大(xbb.outbox.relay.interval-ms)并显式调用,
 * 以保证确定性——后台中继会让"断言事件还没投递"这类测试变得 flaky。
 */
@Component
public class SettlementOutboxRelay extends AbstractOutboxRelay<SettlementOutboxEvent> {

    private final SettlementOutboxRepository outbox;

    SettlementOutboxRelay(SettlementOutboxRepository outbox, ApplicationEventPublisher events,
                           ObjectMapper json) {
        super(events, json);
        this.outbox = outbox;
    }

    @Override
    protected JpaRepository<SettlementOutboxEvent, Long> outbox() {
        return outbox;
    }

    @Scheduled(fixedDelayString = "${xbb.outbox.relay.interval-ms:5000}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("settlementTransactionManager")
    public int publishPending() {
        return relayPending(outbox.findByStatusInOrderByIdAsc(retryable()));
    }
}
