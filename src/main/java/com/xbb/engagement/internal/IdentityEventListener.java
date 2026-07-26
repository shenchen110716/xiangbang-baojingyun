package com.xbb.engagement.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 显式命名:其它域也有同名类 IdentityEventListener,默认 bean 名会撞车
@Component("engagementIdentityEventListener")
class IdentityEventListener {

    private final EngagementVerifiedUserRepository verifiedUsers;

    IdentityEventListener(EngagementVerifiedUserRepository verifiedUsers) {
        this.verifiedUsers = verifiedUsers;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "engagementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.occurredAt()));
    }
}
