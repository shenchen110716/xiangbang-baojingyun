package com.xbb.review.api;

import java.util.List;
import java.util.Optional;

public interface ReviewApi {

    record ReviewView(long id, long applicationId, long raterUserId, List<String> tags,
                       String comment, double score) { }

    record CreditView(long userId, double score, String tier) { }

    /** 提交评价。只有已完成的履约单可评(§5.3 R1),一单一方一次。 */
    void submitReview(long applicationId, long raterUserId, List<String> tags, String comment);

    /** 只返回**已公开**的评价——双盲窗口未结束时查不到对方的(§5.3 R2)。 */
    List<ReviewView> findVisibleReviews(long applicationId);

    Optional<CreditView> findCreditScore(long userId);
}
