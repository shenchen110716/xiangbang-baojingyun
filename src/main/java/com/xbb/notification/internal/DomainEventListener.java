package com.xbb.notification.internal;

import com.xbb.agreement.api.AgreementGenerated;
import com.xbb.broker.api.CommissionGenerated;
import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.fund.api.FundsDisbursed;
import com.xbb.notification.api.NotificationType;
import com.xbb.review.api.CreditScoreChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 统一出口(§4.2):各域只发事件,这里是唯一决定"要不要通知人、通知什么"的地方。
 *
 * <p>依赖方向严格单向:通知域依赖各域的 api,**任何域都不许反过来依赖通知域**——
 * 否则必成环(前面栽过两次)。这也正是"统一出口"能成立的前提。
 */
@Component
class DomainEventListener {

    private final NotificationService notifications;

    DomainEventListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "notificationTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(AgreementGenerated event) {
        notifications.deliver(event.workerUserId(), NotificationType.AGREEMENT_PENDING,
                "有一份劳务协议待签署",
                "签署后才能确认到岗,请尽快处理。",
                event.agreementId());
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由履约域的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "notificationTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        notifications.deliver(event.workerUserId(), NotificationType.ENGAGEMENT_COMPLETED,
                "这一单已完成",
                "现在可以对用工单位做出评价了,评价会影响你的信用分。",
                event.applicationId());
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由资金域的 outbox 中继投递,
     * 中继在自己的事务里 publish——用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑,
     * 那时 outbox 行已是 PUBLISHED,这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "notificationTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(FundsDisbursed event) {
        // 最该通知的一条。金额按分转元,别让用户看到"30000"以为是三万
        notifications.deliver(event.payeeUserId(), NotificationType.WAGE_DISBURSED,
                "工资已发放",
                "本次发放 %d.%02d 元,已通过合规通道代发并开具完税凭证。"
                        .formatted(event.amountCents() / 100, event.amountCents() % 100),
                event.payoutId());
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "notificationTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CommissionGenerated event) {
        notifications.deliver(event.brokerUserId(), NotificationType.COMMISSION_GENERATED,
                "有一笔新佣金",
                "金额 %d.%02d 元,待发放。"
                        .formatted(event.amountCents() / 100, event.amountCents() % 100),
                event.commissionId());
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:所有跨域事件都由发布方的 outbox 中继投递。
     * AFTER_COMMIT 的监听器要等中继事务提交后才跑,那时 outbox 行已是 PUBLISHED,
     * 这里再抛异常事件就永久丢了(理由详见 AbstractOutboxRelay)。
     */
    @EventListener
    @Transactional(transactionManager = "notificationTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(CreditScoreChanged event) {
        // 信用分影响押金档位与派单优先级,不通知等于让用户蒙在鼓里
        notifications.deliver(event.userId(), NotificationType.CREDIT_SCORE_CHANGED,
                "信用分有变化",
                "从 %.0f 变为 %.0f(%s)。信用分会影响押金和接单机会。"
                        .formatted(event.oldScore(), event.newScore(), event.reason()),
                Math.round(event.newScore()));
    }
}
