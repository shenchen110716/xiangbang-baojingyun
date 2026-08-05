package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationRateChangeRepository extends JpaRepository<StationRateChange, Long> {
    List<StationRateChange> findByStationOrgIdOrderByIdDesc(Long stationOrgId);
}
