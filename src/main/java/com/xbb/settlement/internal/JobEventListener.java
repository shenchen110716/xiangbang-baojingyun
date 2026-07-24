package com.xbb.settlement.internal;

import com.xbb.job.api.ApplicationAccepted;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

@Component
class JobEventListener {

    private final SettlementRepository settlements;

    JobEventListener(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(ApplicationAccepted event) {
        settlements.save(new Settlement(
                event.applicationId(), event.jobId(), event.applicantUserId(), event.wageCents()));
    }
}
