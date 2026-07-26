package com.xbb.content.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface BannerRepository extends JpaRepository<Banner, Long> {

    List<Banner> findByStatusOrderByWeightDesc(Banner.Status status);
}
