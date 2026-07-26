package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {

    Optional<Disbursement> findByIdempotencyKey(String idempotencyKey);

    Optional<Disbursement> findByPayoutId(long payoutId);
}
