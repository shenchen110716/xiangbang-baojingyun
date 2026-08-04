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
    /**
      * 一单里已公开的评价。只有这单的当事双方或平台运维看得到。
      *
      * <p>"已公开"管的是**什么时候**能看(双方都评过、或首评满 7 天),
      * 不管**谁**能看 —— 那两件事早先混在一起,结果路人也能读。
      */
    List<ReviewView> findVisibleReviews(long applicationId, long callerUserId);

    Optional<CreditView> findCreditScore(long userId);
}
