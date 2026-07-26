package com.xbb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * outbox 中继的共同部分:把已落库的事件投递到进程内事件总线,失败留在表里等重试。
 *
 * <p>每个域仍要有一个薄薄的子类——`@Transactional("xxxTransactionManager")` 和
 * `@Scheduled` 都要求编译期常量,没法在这里参数化(同各域 `*JpaConfig` 的处境)。
 * 子类只需实现 {@link #outbox()} 并在自己的 `publishPending()` 上打事务与调度注解。
 *
 * <p><b>消费方必须用 {@code @EventListener} 而不是
 * {@code @TransactionalEventListener(AFTER_COMMIT)}</b>:中继在自己的事务里 publish,
 * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已被标成 PUBLISHED,
 * 消费方再抛异常事件照样永久丢失——outbox 就白做了。
 */
public abstract class AbstractOutboxRelay<T extends AbstractOutboxEvent> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOutboxRelay.class);

    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    protected AbstractOutboxRelay(ApplicationEventPublisher events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    protected abstract JpaRepository<T, Long> outbox();

    /** 子类在覆写方法上加事务与调度注解后调用本方法。 */
    protected int relayPending(List<T> pending) {
        int published = 0;
        for (T event : pending) {
            try {
                events.publishEvent(deserialize(event));
                event.markPublished(Instant.now());
                published++;
            } catch (Exception e) {
                // 失败不抛出:一条投递失败不该让整批回滚,更不该让它从表里消失。
                event.markAttemptFailed(e.getMessage());
                log.warn("outbox 投递失败,将重试。eventId={} type={} 第 {} 次 原因={}",
                        event.getEventId(), event.getEventType(), event.getAttemptCount(), e.toString());
            }
            outbox().save(event);
        }
        return published;
    }

    /**
     * 按行里记的类型名反序列化。类型名存的是事件类的 FQCN,所以中继本身
     * 不需要知道自己域里有哪些事件——加一个新事件不用改中继。
     */
    private Object deserialize(T event) throws Exception {
        Class<?> type = Class.forName(event.getEventType());
        return json.readValue(event.getPayload(), type);
    }

    /** 待投递 = 还没投出去的 + 投失败的。失败不是终态,资金链路上的事件不该被悄悄放弃。 */
    protected static List<AbstractOutboxEvent.Status> retryable() {
        return List.of(AbstractOutboxEvent.Status.PENDING, AbstractOutboxEvent.Status.FAILED);
    }
}
