package com.xbb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
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
 * 消费方再抛异常事件照样永久丢失——outbox 就白做了。这条由 OutboxContractTests 守着。
 */
public abstract class AbstractOutboxRelay<T extends AbstractOutboxEvent> {

    private static final Logger log = LoggerFactory.getLogger(AbstractOutboxRelay.class);

    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    /** 第一次失败后的退避时长,之后指数增长(见 {@link OutboxBackoff})。 */
    @Value("${xbb.outbox.retry.base-ms:5000}")
    private long retryBaseMillis;

    /**
     * 重试到这个次数还没成功,就升级成 ERROR 日志。
     *
     * <p>这是**告警接入点**:WARN 会被日常噪音淹没,而"一条事件反复投不出去"
     * 在资金链路上意味着有人的钱或佣金还没到账,必须有人看见。
     */
    @Value("${xbb.outbox.stuck-threshold:5}")
    private int stuckThreshold;

    protected AbstractOutboxRelay(ApplicationEventPublisher events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    protected abstract OutboxEventRepository<T> outbox();

    /** 域名。从包名推出来(com.xbb.fund.internal → fund),省得每个子类再写一遍。 */
    public String domain() {
        String pkg = getClass().getPackageName();
        String tail = pkg.substring("com.xbb.".length());
        return tail.contains(".") ? tail.substring(0, tail.indexOf('.')) : tail;
    }

    /** 运维视图:反复投不出去、需要人看的事件。 */
    public List<StuckEvent> stuck() {
        return outbox().findStuck(AbstractOutboxEvent.Status.FAILED, stuckThreshold).stream()
                .map(e -> new StuckEvent(domain(), e.getEventId(), e.getEventType(),
                        e.getAttemptCount(), e.getLastError(), e.getNextAttemptAt()))
                .toList();
    }

    /**
     * 人工重放:确认问题已修复后,把失败计数与退避清零,让它立刻重新排队。
     *
     * @return 是否找到了这条事件
     */
    public boolean replay(String eventId) {
        return outbox().findByEventId(eventId).map(event -> {
            event.resetForReplay();
            outbox().save(event);
            log.info("outbox 事件已人工重放。domain={} eventId={}", domain(), eventId);
            return true;
        }).orElse(false);
    }

    /** 一条卡死的事件,给运维看的最小信息量:是什么、卡了多久、为什么。 */
    public record StuckEvent(String domain, String eventId, String eventType,
                              int attemptCount, String lastError, Instant nextAttemptAt) { }

    /** 子类在覆写方法上加事务与调度注解后调用本方法。 */
    protected int relayPending(List<T> pending) {
        int published = 0;
        for (T event : pending) {
            try {
                OutboxEventMulticasterConfig.drainFailures();   // 清掉上一轮的残留
                events.publishEvent(deserialize(event));

                // 广播器把每个监听器的异常都接住了(见 OutboxEventMulticasterConfig),
                // 所以走到这里不代表全都成功——要主动check。任一消费方失败就整条重试,
                // 已成功的那些靠自身幂等吸收。
                java.util.List<Throwable> failures = OutboxEventMulticasterConfig.drainFailures();
                if (!failures.isEmpty()) {
                    throw new IllegalStateException(
                            failures.size() + " 个消费方处理失败,首个原因: " + failures.get(0), failures.get(0));
                }
                event.markPublished(Instant.now());
                published++;
            } catch (Exception e) {
                // 失败不抛出:一条投递失败不该让整批回滚,更不该让它从表里消失。
                Duration delay = OutboxBackoff.delayAfter(
                        event.getAttemptCount() + 1, Duration.ofMillis(retryBaseMillis));
                event.markAttemptFailed(e.getMessage(), Instant.now().plus(delay));
                report(event, e, delay);
            }
            outbox().save(event);
        }
        return published;
    }

    private void report(T event, Exception cause, Duration delay) {
        if (event.isStuck(stuckThreshold)) {
            log.error("outbox 事件反复投递失败,已达 {} 次,需要人工介入。"
                            + "eventId={} type={} 最近一次原因={}",
                    event.getAttemptCount(), event.getEventId(), event.getEventType(), cause.toString());
        } else {
            log.warn("outbox 投递失败,{} 后重试。eventId={} type={} 第 {} 次 原因={}",
                    delay, event.getEventId(), event.getEventType(), event.getAttemptCount(), cause.toString());
        }
    }

    /**
     * 按行里记的类型名反序列化。类型名存的是事件类的 FQCN,所以中继本身
     * 不需要知道自己域里有哪些事件——加一个新事件不用改中继。
     */
    private Object deserialize(T event) throws Exception {
        Class<?> type = Class.forName(event.getEventType());
        return json.readValue(event.getPayload(), type);
    }

    protected int stuckThreshold() {
        return stuckThreshold;
    }
}
