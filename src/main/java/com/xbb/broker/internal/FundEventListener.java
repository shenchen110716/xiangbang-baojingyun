package com.xbb.broker.internal;

import com.xbb.broker.api.CommissionGenerated;
import com.xbb.fund.api.FundsDisbursed;
import org.springframework.context.ApplicationEventPublisher;
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

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(FundsDisbursed event) {
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
