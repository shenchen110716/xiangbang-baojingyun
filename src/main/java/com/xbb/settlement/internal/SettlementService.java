package com.xbb.settlement.internal;

import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.api.SettlementVoided;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class SettlementService implements SettlementApi {

    private final SettlementRepository settlements;
    private final SettlementOutboxRepository outbox;
    private final ObjectMapper json;

    SettlementService(SettlementRepository settlements,
                     SettlementOutboxRepository outbox, ObjectMapper json) {
        this.settlements = settlements;
        this.outbox = outbox;
        this.json = json;
    }

    private String serialize(Object event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化不了就别让这步业务成功——事件发不出去,下游永远补不回来
            throw new IllegalStateException("事件无法序列化: " + event, e);
        }
    }

    @Override
    @Transactional("settlementTransactionManager")
    public void voidSettlement(long settlementId, String reason) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("结算记录不存在"));
        settlement.voidSettlement(reason);
        settlements.save(settlement);
        SettlementVoided voided = new SettlementVoided(settlementId, reason, Instant.now());
        outbox.save(new SettlementOutboxEvent(java.util.UUID.randomUUID().toString(),
                SettlementVoided.class.getName(), serialize(voided)));
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findById(long settlementId) {
        return settlements.findById(settlementId).map(this::toView);
    }

    @Override
    @Transactional(transactionManager = "settlementTransactionManager", readOnly = true)
    public Optional<SettlementView> findByApplicationId(long applicationId) {
        return settlements.findByApplicationId(applicationId).map(this::toView);
    }

    private SettlementView toView(Settlement s) {
        return new SettlementView(
                s.getId(), s.getApplicationId(), s.getJobId(), s.getWorkerUserId(),
                s.getAmountCents(), s.getStatus(), s.getVoidReason());
    }
}
