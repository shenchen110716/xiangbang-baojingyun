package com.xbb.broker.internal;

import com.xbb.broker.api.CommissionGenerated;
import com.xbb.settlement.api.SettlementPaid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component
class SettlementEventListener {

    private final InvitationRepository invitations;
    private final CommissionRepository commissions;
    private final ApplicationEventPublisher events;

    SettlementEventListener(InvitationRepository invitations, CommissionRepository commissions,
                             ApplicationEventPublisher events) {
        this.invitations = invitations;
        this.commissions = commissions;
        this.events = events;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "brokerTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(SettlementPaid event) {
        // 工人没绑经纪人是正常路径,不是错误——直接不生成佣金记录,不抛异常。
        invitations.findByWorkerUserId(event.workerUserId()).ifPresent(invitation -> {
            long amountCents = event.amountCents() * Commission.RATE_PERCENT / 100;
            Commission commission = commissions.save(new Commission(
                    invitation.getBrokerUserId(), event.workerUserId(), event.settlementId(), amountCents));
            events.publishEvent(new CommissionGenerated(
                    commission.getId(), invitation.getBrokerUserId(), amountCents, Instant.now()));
        });
    }
}
