package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationCooperationRepository extends JpaRepository<StationCooperation, Long> {
    List<StationCooperation> findByStationOrgIdOrderByIdDesc(long stationOrgId);
    List<StationCooperation> findByPartnerOrgIdOrderByIdDesc(long partnerOrgId);
}
