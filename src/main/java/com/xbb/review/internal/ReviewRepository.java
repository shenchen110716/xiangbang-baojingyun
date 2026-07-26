package com.xbb.review.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByApplicationId(long applicationId);

    Optional<Review> findByApplicationIdAndRaterUserId(long applicationId, long raterUserId);

    List<Review> findByRateeUserIdAndVisibleTrue(long rateeUserId);
}
