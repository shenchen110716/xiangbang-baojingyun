package com.xbb.org.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationMasterChangeRepository extends JpaRepository<StationMasterChange, Long> {
    List<StationMasterChange> findByOrgIdOrderByIdDesc(long orgId);
}
