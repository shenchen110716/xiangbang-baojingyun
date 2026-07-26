package com.xbb.job.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface JobRepository extends JpaRepository<Job, Long> {

    List<Job> findByOrgId(long orgId);
}
