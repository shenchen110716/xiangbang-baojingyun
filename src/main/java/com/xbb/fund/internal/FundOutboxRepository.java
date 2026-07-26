package com.xbb.fund.internal;

import com.xbb.AbstractOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FundOutboxRepository extends JpaRepository<FundOutboxEvent, Long> {

    List<FundOutboxEvent> findByStatusInOrderByIdAsc(List<AbstractOutboxEvent.Status> statuses);
}
