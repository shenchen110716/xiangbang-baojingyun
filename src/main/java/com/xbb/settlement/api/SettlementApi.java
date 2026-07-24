package com.xbb.settlement.api;

import com.xbb.settlement.internal.Settlement;
import java.util.Optional;

public interface SettlementApi {

    record SettlementView(long id, long applicationId, long jobId, long workerUserId,
                           long amountCents, Settlement.Status status, String voidReason) { }

    void pay(long settlementId);

    void voidSettlement(long settlementId, String reason);

    Optional<SettlementView> findById(long settlementId);

    Optional<SettlementView> findByApplicationId(long applicationId);
}
