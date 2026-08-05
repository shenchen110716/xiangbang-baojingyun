package com.xbb.broker.internal;

import com.xbb.identity.api.UserVerified;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


// 显式命名:其它域也有同名类 IdentityEventListener,默认 bean 名会撞车
@Component("brokerIdentityEventListener")
class IdentityEventListener {

    private final BrokerVerifiedUserRepository verifiedUsers;
    private final ShareUpgradeService upgrades;

    IdentityEventListener(BrokerVerifiedUserRepository verifiedUsers, ShareUpgradeService upgrades) {
        this.upgrades = upgrades;
        this.verifiedUsers = verifiedUsers;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(UserVerified event) {
        verifiedUsers.save(new VerifiedUser(event.userId(), event.occurredAt()));
        // 实名副本刚落地:如果这个人此前因"未实名"被挡下过升级,现在补上。
        // 不补的话,只带来一单的分享人会**永久错过**那次升级机会
        upgrades.onVerified(event.userId());
    }
}
