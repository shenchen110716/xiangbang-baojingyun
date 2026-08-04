package com.xbb.settlement.api;

import com.xbb.settlement.internal.Settlement;
import java.util.List;
import java.util.Optional;

public interface SettlementApi {

    record SettlementView(long id, long applicationId, long jobId, long workerUserId,
                           long amountCents, Settlement.Status status, String voidReason) { }

    void voidSettlement(long settlementId, String reason, long callerUserId);

    Optional<SettlementView> findById(long settlementId);

    Optional<SettlementView> findByApplicationId(long applicationId);

    /** 我的工资单列表。查询条件即归属,不存在看到别人工资的路径。 */
    List<SettlementView> listMySettlements(long workerUserId);
}
