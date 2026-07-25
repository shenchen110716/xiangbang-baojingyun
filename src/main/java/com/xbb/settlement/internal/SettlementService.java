package com.xbb.settlement.internal;

import com.xbb.settlement.api.SettlementApi;
import com.xbb.settlement.api.SettlementVoided;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
class SettlementService implements SettlementApi {

    private final SettlementRepository settlements;
    private final ApplicationEventPublisher events;

    SettlementService(SettlementRepository settlements, ApplicationEventPublisher events) {
        this.settlements = settlements;
        this.events = events;
    }

    @Override
    @Transactional("settlementTransactionManager")
    public void voidSettlement(long settlementId, String reason) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("结算记录不存在"));
        settlement.voidSettlement(reason);
        settlements.save(settlement);
        events.publishEvent(new SettlementVoided(settlementId, reason, Instant.now()));
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
