package com.xbb.settlement.internal;

import com.xbb.AbstractOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SettlementOutboxRepository extends JpaRepository<SettlementOutboxEvent, Long> {

    List<SettlementOutboxEvent> findByStatusInOrderByIdAsc(List<AbstractOutboxEvent.Status> statuses);

    Optional<SettlementOutboxEvent> findByEventId(String eventId);
}
