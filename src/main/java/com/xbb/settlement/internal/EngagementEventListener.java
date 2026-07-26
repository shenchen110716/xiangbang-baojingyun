package com.xbb.settlement.internal;

import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.settlement.api.SettlementCalculated;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

import static org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT;

/**
 * 结算的触发点是**履约完成**,不是录用(主文档 §9.3 枢纽事件)。
 * Plan7 当时因为履约域没有完成态,临时挂在 ApplicationAccepted 上(录用即视为可结算),
 * 那是记录在案的已知缺口;Plan9 补上完成态后迁到这里。
 */
@Component
class EngagementEventListener {

    private final SettlementRepository settlements;
    private final ApplicationEventPublisher events;

    EngagementEventListener(SettlementRepository settlements, ApplicationEventPublisher events) {
        this.settlements = settlements;
        this.events = events;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        Settlement settlement = settlements.save(new Settlement(
                event.applicationId(), event.jobId(), event.workerUserId(), event.wageCents()));
        events.publishEvent(new SettlementCalculated(
                settlement.getId(), event.applicationId(), event.workerUserId(), event.wageCents(), Instant.now()));
    }
}
