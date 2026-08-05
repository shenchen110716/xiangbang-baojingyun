package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CooperationOperatorRepository extends JpaRepository<CooperationOperator, Long> {
    List<CooperationOperator> findByCooperationIdAndActiveTrue(long cooperationId);
    Optional<CooperationOperator> findByCooperationIdAndUserIdAndActiveTrue(long cooperationId, long userId);
}
