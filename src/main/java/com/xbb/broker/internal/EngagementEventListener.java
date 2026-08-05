package com.xbb.broker.internal;

import com.xbb.engagement.api.ApplicationSubmitted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报名触发的自动升级。
 *
 * <p>只在"升级门槛 = 0"(对方报名即升级)时才真的计数 ——
 * 判断在 {@link ShareUpgradeService#onDeal} 里,这里不做业务判断,
 * 免得同一条规则散在两处、改一处忘一处。
 *
 * <p>显式命名:结算域也有个同名类,默认 bean 名会撞车。
 */
@Component("brokerEngagementEventListener")
class EngagementEventListener {

    private final ShareUpgradeService upgrades;

    EngagementEventListener(ShareUpgradeService upgrades) {
        this.upgrades = upgrades;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递,
     * 用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ApplicationSubmitted event) {
        // **不经分享自己直接报名的,这一单也要有主** —— 归默认站长。
        // 否则佣金那几档全不分,钱留在池子里,而平台的经营网点对不上账
        upgrades.ensureAttributed(event.applicantUserId());
        upgrades.onDeal(event.applicantUserId(), event.applicationId(), true);
    }
}
