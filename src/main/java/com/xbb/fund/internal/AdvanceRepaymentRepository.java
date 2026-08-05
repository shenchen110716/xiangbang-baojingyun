package com.xbb.fund.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdvanceRepaymentRepository extends JpaRepository<AdvanceRepayment, Long> {

    List<AdvanceRepayment> findByAdvanceIdOrderByIdAsc(long advanceId);

    List<AdvanceRepayment> findBySettlementId(long settlementId);
}
