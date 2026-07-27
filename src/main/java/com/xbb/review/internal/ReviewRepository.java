package com.xbb.review.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByApplicationId(long applicationId);

    Optional<Review> findByApplicationIdAndRaterUserId(long applicationId, long raterUserId);

    List<Review> findByRateeUserIdAndVisibleTrue(long rateeUserId);

    /** 信用分要把**全部**收到的评价算进去,不只公开的那些。 */
    List<Review> findByRateeUserId(long rateeUserId);
}
