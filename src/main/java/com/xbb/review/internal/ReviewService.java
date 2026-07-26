package com.xbb.review.internal;

import com.xbb.review.api.CreditScoreChanged;
import com.xbb.review.api.ReviewApi;
import com.xbb.review.api.ReviewSubmitted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
class ReviewService implements ReviewApi {

    /** 双盲窗口(§5.3 R2):双方都提交、或首评满 7 天,才同时公开。 */
    static final Duration BLIND_WINDOW = Duration.ofDays(7);

    private final ReviewRepository reviews;
    private final CompletedEngagementRepository completedEngagements;
    private final CreditScoreRepository creditScores;
    private final CreditCalculator calculator;
    private final ApplicationEventPublisher events;

    ReviewService(ReviewRepository reviews, CompletedEngagementRepository completedEngagements,
                   CreditScoreRepository creditScores, CreditCalculator calculator,
                   ApplicationEventPublisher events) {
        this.reviews = reviews;
        this.completedEngagements = completedEngagements;
        this.creditScores = creditScores;
        this.calculator = calculator;
        this.events = events;
    }

    @Override
    @Transactional("reviewTransactionManager")
    public void submitReview(long applicationId, long raterUserId, List<String> tags, String comment) {
        // R1 强绑履约:只有完成的履约单可评,"从根上断掉刷分"
        CompletedEngagement engagement = completedEngagements.findById(applicationId)
                .orElseThrow(() -> new IllegalStateException("只有已完成的履约单可以评价"));

        ReviewTag.Direction direction = directionOf(engagement, raterUserId);
        if (reviews.findByApplicationIdAndRaterUserId(applicationId, raterUserId).isPresent()) {
            throw new IllegalStateException("同一履约单每人只能评价一次");
        }

        double score = ReviewTag.score(direction, tags);
        Review review = reviews.save(new Review(
                applicationId, raterUserId,
                direction == ReviewTag.Direction.ORG_RATES_WORKER ? engagement.getWorkerUserId() : null,
                direction == ReviewTag.Direction.WORKER_RATES_ORG ? engagement.getOrgId() : null,
                direction, tags, comment, score));

        revealIfBothSubmitted(applicationId);
        events.publishEvent(new ReviewSubmitted(review.getId(), applicationId, raterUserId, score, Instant.now()));

        if (direction == ReviewTag.Direction.ORG_RATES_WORKER) {
            recalculateCredit(engagement.getWorkerUserId(), "收到新评价");
        }
    }

    /**
     * 评价人必须是该履约单的两方之一。工厂侧的"人"是岗位所属组织的法人代表——
     * 履约完成副本里存的是 orgId,法人代表是谁由 engagement 域鉴权时保证,
     * 这里只区分"是不是那个工人",不是工人就按工厂侧处理。
     */
    private ReviewTag.Direction directionOf(CompletedEngagement engagement, long raterUserId) {
        return raterUserId == engagement.getWorkerUserId()
                ? ReviewTag.Direction.WORKER_RATES_ORG
                : ReviewTag.Direction.ORG_RATES_WORKER;
    }

    /** R2 双盲:双方都提交后一起公开。7 天到期那一支在读取时判定,见 isRevealed。 */
    private void revealIfBothSubmitted(long applicationId) {
        List<Review> all = reviews.findByApplicationId(applicationId);
        if (all.size() >= 2) {
            all.forEach(Review::reveal);
            reviews.saveAll(all);
        }
    }

    @Override
    @Transactional(transactionManager = "reviewTransactionManager", readOnly = true)
    public List<ReviewView> findVisibleReviews(long applicationId) {
        List<Review> all = reviews.findByApplicationId(applicationId);
        List<ReviewView> result = new ArrayList<>();
        for (Review review : all) {
            if (isRevealed(review, all)) {
                result.add(new ReviewView(review.getId(), review.getApplicationId(), review.getRaterUserId(),
                        review.getTags(), review.getComment(), review.getScore()));
            }
        }
        return result;
    }

    /**
     * 公开判定 = 已标记 visible(双方都提交过) **或** 首评已满 7 天。
     *
     * <p>7 天这一支用**读时判定**而不是定时任务:域内没有调度基础设施,为这一个规则
     * 引入 quartz 是明显更大的决定。读时判定效果等价,且没有定时任务的运维负担。
     */
    private boolean isRevealed(Review review, List<Review> allForApplication) {
        if (review.isVisible()) return true;
        Instant firstSubmittedAt = allForApplication.stream()
                .map(Review::getCreatedAt)
                .min(Instant::compareTo)
                .orElse(review.getCreatedAt());
        return Duration.between(firstSubmittedAt, Instant.now()).compareTo(BLIND_WINDOW) >= 0;
    }

    @Override
    @Transactional(transactionManager = "reviewTransactionManager", readOnly = true)
    public Optional<CreditView> findCreditScore(long userId) {
        return creditScores.findById(userId).map(record -> new CreditView(
                record.getUserId(), record.getScore(),
                CreditCalculator.CreditTier.of(record.getScore()).name()));
    }

    /** 履约完成或收到评价后重算,变了才发事件。 */
    void recalculateCredit(long workerUserId, String reason) {
        CreditCalculator.CreditHistory history = buildHistory(workerUserId);
        double newScore = calculator.calculate(history, Instant.now());

        CreditScoreRecord record = creditScores.findById(workerUserId).orElse(null);
        double oldScore = record == null ? CreditCalculator.NEW_USER_SCORE : record.getScore();
        if (record == null) {
            creditScores.save(new CreditScoreRecord(workerUserId, newScore));
        } else {
            record.update(newScore);
            creditScores.save(record);
        }
        if (Double.compare(oldScore, newScore) != 0) {
            events.publishEvent(new CreditScoreChanged(workerUserId, oldScore, newScore, reason, Instant.now()));
        }
    }

    private CreditCalculator.CreditHistory buildHistory(long workerUserId) {
        List<CreditCalculator.Engagement> engagements = completedEngagements.findAll().stream()
                .filter(e -> e.getWorkerUserId() == workerUserId)
                .map(e -> new CreditCalculator.Engagement(e.getCompletedAt(), true))
                .toList();
        List<CreditCalculator.ReceivedReview> received = reviews.findAll().stream()
                .filter(r -> r.getRateeUserId() != null && r.getRateeUserId() == workerUserId)
                .map(r -> new CreditCalculator.ReceivedReview(
                        r.getCreatedAt(), r.getScore(), r.getTags().contains(ReviewTag.MID_QUIT)))
                .toList();
        boolean repaired = calculator.qualifiesForRepair(engagements, received);
        return new CreditCalculator.CreditHistory(engagements, received, repaired);
    }
}
