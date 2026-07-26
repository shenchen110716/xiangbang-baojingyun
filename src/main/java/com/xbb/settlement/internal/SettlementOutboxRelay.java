package com.xbb.settlement.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxEvent;
import com.xbb.settlement.api.SettlementCalculated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * outbox 中继:把已落库的事件投递到进程内事件总线。
 *
 * <p>解决的是 Plan3 记下的那个问题:此前监听器是同步 AFTER_COMMIT,
 * 监听器一抛异常,**原始事务已经提交、数据不会回滚**,事件就永久丢了,
 * 没有任何机制会重试。资金链路上这不可接受。
 *
 * <p>现在:事件与业务数据同事务落库 → 中继投递 → 失败只是标记 FAILED 并累加次数,
 * 事件仍在表里,下一轮会重试。投递语义是**至少一次**,所以消费方必须按 eventId 幂等。
 */
@Component
public class SettlementOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(SettlementOutboxRelay.class);

    private final SettlementOutboxRepository outbox;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    SettlementOutboxRelay(SettlementOutboxRepository outbox, ApplicationEventPublisher events,
                           ObjectMapper json) {
        this.outbox = outbox;
        this.events = events;
        this.json = json;
    }

    /**
     * 生产由调度驱动;测试里把间隔调到极大(xbb.outbox.relay.interval-ms)并显式调用,
     * 以保证确定性——后台中继会让"断言事件还没投递"这类测试变得 flaky。
     */
    @Scheduled(fixedDelayString = "${xbb.outbox.relay.interval-ms:5000}")
    public void relayScheduled() {
        publishPending();
    }

    @Transactional("settlementTransactionManager")
    public int publishPending() {
        List<SettlementOutboxEvent> pending = outbox.findByStatusInOrderByIdAsc(
                List.of(AbstractOutboxEvent.Status.PENDING, AbstractOutboxEvent.Status.FAILED));
        int published = 0;
        for (SettlementOutboxEvent event : pending) {
            try {
                // 消费方是 @EventListener(不是 AFTER_COMMIT),在这里同步执行;
                // 它抛的异常会落到下面的 catch,事件因此留在表里可重试。
                events.publishEvent(json.readValue(event.getPayload(), SettlementCalculated.class));
                event.markPublished(Instant.now());
                published++;
            } catch (Exception e) {
                // 失败不抛出:一条事件投递失败不该让整批中继回滚,
                // 更不该让它从表里消失。留在表里下一轮重试。
                event.markAttemptFailed(e.getMessage());
                log.warn("outbox 投递失败,将重试。eventId={} 第 {} 次 原因={}",
                        event.getEventId(), event.getAttemptCount(), e.toString());
            }
            outbox.save(event);
        }
        return published;
    }
}
