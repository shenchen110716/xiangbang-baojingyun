package com.xbb.settlement.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xbb.engagement.api.EngagementCompleted;
import com.xbb.settlement.api.SettlementCalculated;
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
    private final SettlementOutboxRepository outbox;
    private final ObjectMapper json;

    EngagementEventListener(SettlementRepository settlements, SettlementOutboxRepository outbox,
                             ObjectMapper json) {
        this.settlements = settlements;
        this.outbox = outbox;
        this.json = json;
    }

    // 同步(非 @Async)AFTER_COMMIT,理由见 org.internal.IdentityEventListener 的注释(审计修复)。
    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(transactionManager = "settlementTransactionManager", propagation = Propagation.REQUIRES_NEW)
    void on(EngagementCompleted event) {
        // 幂等(§9.1:消费方按 eventId 去重)。EngagementCompleted 可能被重投,
        // 重复处理会生成第二条结算记录——那是真金白银的重复。
        if (outbox.findByEventId(event.eventId()).isPresent()) return;

        Settlement settlement = settlements.save(new Settlement(
                event.applicationId(), event.jobId(), event.workerUserId(), event.wageCents()));

        // 关键:结算记录与 outbox 行**在同一个事务里**落库。
        // 要么都成功要么都回滚,不会出现"结算生成了但下游永远收不到通知"。
        SettlementCalculated calculated = new SettlementCalculated(
                settlement.getId(), event.applicationId(), event.workerUserId(),
                event.wageCents(), Instant.now());
        outbox.save(new SettlementOutboxEvent(
                event.eventId(), SettlementCalculated.class.getName(), serialize(calculated)));
    }

    private String serialize(SettlementCalculated event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结算事件序列化失败", e);
        }
    }
}
