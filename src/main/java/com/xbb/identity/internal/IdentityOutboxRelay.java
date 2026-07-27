package com.xbb.identity.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.AbstractOutboxRelay;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import com.xbb.OutboxEventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IdentityOutboxRelay extends AbstractOutboxRelay<IdentityOutboxEvent> {

    private final IdentityOutboxRepository outbox;
    /** 自身的代理引用,见 relayScheduled 的注释。 */
    private final ObjectProvider<IdentityOutboxRelay> self;

    IdentityOutboxRelay(IdentityOutboxRepository outbox, ApplicationEventPublisher events, ObjectMapper json,
                           ObjectProvider<IdentityOutboxRelay> self) {
        super(events, json);
        this.outbox = outbox;
        this.self = self;
    }

    @Override
    protected OutboxEventRepository<IdentityOutboxEvent> outbox() {
        return outbox;
    }

    // 每个域可单独调间隔,默认回落到全局值。需要"由测试自己驱动投递"的用例
    // 只关掉它关心的那一个中继——全关掉的话连搭建前置数据都跑不动了。
    @Scheduled(fixedDelayString =
            "${xbb.outbox.relay.identity.interval-ms:${xbb.outbox.relay.interval-ms:5000}}")
    public void relayScheduled() {
        // **必须经过代理调用**:直接 this.publishPending() 会绕过 Spring 的事务代理,
        // @Transactional 静默失效——那样 lockPendingBatch 的 FOR UPDATE SKIP LOCKED
        // 只在仓库自己的短事务里持锁,SELECT 一返回锁就没了,多实例会重复投递,
        // 而且批内每次 save 各自提交,"整批一致"根本不存在。
        // 这个 bug 之前没被测出来,是因为测试全都直接调注入的代理 bean,
        // 测的路径和调度器跑的路径不是同一条。
        self.getObject().publishPending();
    }

    @Transactional("identityTransactionManager")
    public int publishPending() {
        return relayPending(outbox.lockPendingBatch());
    }
}
