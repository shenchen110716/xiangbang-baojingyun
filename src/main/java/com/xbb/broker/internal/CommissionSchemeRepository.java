package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommissionSchemeRepository extends JpaRepository<CommissionScheme, Long> {

    Optional<CommissionScheme> findByStationOrgIdAndCategory(long stationOrgId, String category);

    /** 平台默认那条。 */
    Optional<CommissionScheme> findByStationOrgIdIsNullAndCategory(String category);

    List<CommissionScheme> findByStationOrgId(long stationOrgId);

    List<CommissionScheme> findByStationOrgIdIsNull();
}
