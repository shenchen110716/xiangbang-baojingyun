package com.xbb.review.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompletedEngagementRepository extends JpaRepository<CompletedEngagement, Long> {

    List<CompletedEngagement> findByWorkerUserId(long workerUserId);
}
