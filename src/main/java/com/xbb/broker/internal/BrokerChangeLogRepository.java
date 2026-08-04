package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrokerChangeLogRepository extends JpaRepository<BrokerChangeLog, Long> {

    List<BrokerChangeLog> findByBrokerUserIdOrderByChangedAtDesc(long brokerUserId);

    List<BrokerChangeLog> findTop100ByOrderByChangedAtDesc();
}
