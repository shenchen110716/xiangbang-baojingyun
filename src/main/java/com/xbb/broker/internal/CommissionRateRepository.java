package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface CommissionRateRepository extends JpaRepository<CommissionRate, Long> {

    Optional<CommissionRate> findByCategoryAndRegionCode(String category, String regionCode);

    /** 全国兜底那条。 */
    Optional<CommissionRate> findByCategoryAndRegionCodeIsNull(String category);

    List<CommissionRate> findByCategoryOrderByRegionCodeAsc(String category);

    List<CommissionRate> findAllByOrderByCategoryAscRegionCodeAsc();
}
