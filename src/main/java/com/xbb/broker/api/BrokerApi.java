package com.xbb.broker.api;

import com.xbb.broker.internal.Commission;
import java.util.Optional;

public interface BrokerApi {

    record BrokerView(long userId, boolean registered) { }

    record CommissionView(long id, long brokerUserId, long workerUserId, long settlementId,
                           long amountCents, Commission.Status status) { }

    void registerBroker(long userId);

    long bindWorker(long brokerUserId, long workerUserId);

    void payCommission(long commissionId);

    Optional<BrokerView> findBroker(long userId);

    Optional<CommissionView> findCommission(long commissionId);
}
