package com.xbb.broker.api;

import java.util.Optional;

public interface BrokerApi {

    record BrokerView(long userId, boolean registered) { }

    void registerBroker(long userId);

    long bindWorker(long brokerUserId, long workerUserId);

    Optional<BrokerView> findBroker(long userId);
}
