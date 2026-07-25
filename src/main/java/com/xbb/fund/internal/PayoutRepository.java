package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findBySettlementId(long settlementId);
}
