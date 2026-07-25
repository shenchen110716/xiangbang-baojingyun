package com.xbb.settlement.internal;

import com.xbb.job.api.ApplicationAccepted;
import com.xbb.settlement.api.SettlementCalculated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component
class JobEventListener {

    private final SettlementRepository settlements;
    private final ApplicationEventPublisher events;

    JobEventListener(SettlementRepository settlements, ApplicationEventPublisher events) {
        this.settlements = settlements;
        this.events = events;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ApplicationAccepted event) {
        Settlement settlement = settlements.save(new Settlement(
                event.applicationId(), event.jobId(), event.applicantUserId(), event.wageCents()));
        events.publishEvent(new SettlementCalculated(
                settlement.getId(), event.applicationId(), event.applicantUserId(), event.wageCents(), Instant.now()));
    }
}
