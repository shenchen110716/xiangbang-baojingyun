package com.xbb.ops.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface RegionRepository extends JpaRepository<Region, String> {

    List<Region> findByLevelOrderByCodeAsc(short level);

    List<Region> findByParentCodeOrderByCodeAsc(String parentCode);
}
