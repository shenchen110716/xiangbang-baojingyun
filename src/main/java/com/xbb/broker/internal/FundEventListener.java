package com.xbb.broker.internal;

import com.xbb.broker.api.CommissionGenerated;
import com.xbb.fund.api.FundsDisbursed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

// 佣金触发源:原先订阅 settlement.SettlementPaid,现在 settlement 已不再直接发钱
// (结算⊥资金拆分,见 Plan6),真正"钱已经付了"的信号来自 fund.FundsDisbursed。
// 显式命名:settlement 域也有个同名类 FundEventListener,默认 bean 名会撞车
@Component("brokerFundEventListener")
class FundEventListener {

    private final InvitationRepository invitations;
    private final CommissionRepository commissions;
    private final ApplicationEventPublisher events;

    FundEventListener(InvitationRepository invitations, CommissionRepository commissions,
                       ApplicationEventPublisher events) {
        this.invitations = invitations;
        this.commissions = commissions;
        this.events = events;
    }

    /**
     * `@EventListener` 而非 AFTER_COMMIT:该事件由资金域的 outbox 中继投递,
     * 中继在自己的事务里 publish——用 AFTER_COMMIT 的话本方法要等中继事务提交后才跑,
     * 那时 outbox 行已是 PUBLISHED,这里再抛异常事件就永久丢了。
     */
    @EventListener
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(FundsDisbursed event) {
        // 中继是至少一次投递:同一笔发放会重复到达。settlement_id UNIQUE 已经挡住了
        // 第二笔佣金,但仅靠它会让中继撞约束、永远重试,事件卡死在 FAILED。
        if (commissions.findBySettlementId(event.settlementId()).isPresent()) {
            return;
        }
        // 工人没绑经纪人是正常路径,不是错误——直接不生成佣金记录,不抛异常。
        invitations.findByWorkerUserId(event.payeeUserId()).ifPresent(invitation -> {
            long amountCents = event.amountCents() * Commission.RATE_PERCENT / 100;
            Commission commission = commissions.save(new Commission(
                    invitation.getBrokerUserId(), event.payeeUserId(), event.settlementId(), amountCents));
            events.publishEvent(new CommissionGenerated(
                    commission.getId(), invitation.getBrokerUserId(), amountCents, Instant.now()));
        });
    }
}
