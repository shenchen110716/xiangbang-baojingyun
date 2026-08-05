package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationRateRepository extends JpaRepository<StationRate, Long> {

    Optional<StationRate> findByStationOrgIdAndCategory(long stationOrgId, String category);

    /** 平台默认那条。 */
    Optional<StationRate> findByStationOrgIdIsNullAndCategory(String category);

    List<StationRate> findByStationOrgId(long stationOrgId);

    List<StationRate> findByStationOrgIdIsNull();
}
