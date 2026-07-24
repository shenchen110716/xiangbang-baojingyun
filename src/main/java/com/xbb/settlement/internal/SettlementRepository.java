package com.xbb.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findByApplicationId(long applicationId);
}
