package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findBySettlementId(long settlementId);

    /** 我的待发放/已发放记录。 */
    List<Payout> findByPayeeUserIdOrderByIdDesc(long payeeUserId);
}
