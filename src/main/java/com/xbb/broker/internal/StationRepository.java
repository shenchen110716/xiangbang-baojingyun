package com.xbb.broker.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StationRepository extends JpaRepository<Station, Long> {

    List<Station> findAllByOrderByOrgIdAsc();
}
