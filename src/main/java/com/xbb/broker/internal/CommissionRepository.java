package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import java.util.Optional;

public interface CommissionRepository extends JpaRepository<Commission, Long> {
    Optional<Commission> findBySettlementId(long settlementId);

    /** 一笔结算的全部分账。现在一笔会生成多条(主动/被动/服务站)。 */
    List<Commission> findAllBySettlementId(long settlementId);
}
