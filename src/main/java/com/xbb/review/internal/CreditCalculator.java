package com.xbb.review.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 信用分(主文档 §5.3.2),纯函数——时间显式传入,不在内部调 Instant.now(),
 * 否则时间衰减根本没法测。
 *
 * <pre>
 * credit = 100 × ( 0.5 × 履约率_衰减 + 0.3 × 评价均值_归一 + 0.2 × (1 − 违约惩罚) )
 * 履约率_衰减 = Σ(完成单 × e^(−λt)) / Σ(接单 × e^(−λt)),半衰期 90 天
 * 评价均值_归一 = "工厂评工人"均值 / 5;无评价则以履约率兜底
 * 违约惩罚     = 近 90 天失约次数的饱和函数 1 − e^(−0.7n)
 * </pre>
 *
 * <p>量纲 0–100(不用 350–950,"蓝领理解成本高")。新人 60 分起——
 * "0 分等于把新人判死刑"。
 */
public class CreditCalculator {

    /** 新人起始分(§5.3.2):中性,不是 0。 */
    public static final double NEW_USER_SCORE = 60.0;

    /** 半衰期 90 天(§5.3.2)。时间衰减是灵魂:半年前的失约与上周的失约权重必须不同。 */
    private static final double HALF_LIFE_DAYS = 90.0;

    private static final double LAMBDA = Math.log(2) / HALF_LIFE_DAYS;

    /** 违约惩罚饱和系数(§5.3.2:1 − e^(−0.7n))。 */
    private static final double PENALTY_K = 0.7;

    private static final double W_FULFILLMENT = 0.5;
    private static final double W_REVIEW = 0.3;
    private static final double W_PENALTY = 0.2;

    private static final int RECENT_DAYS = 90;

    /** 一次接单记录。completed=false 表示接了没干完。 */
    public record Engagement(Instant occurredAt, boolean completed) { }

    /** 一次"工厂评工人"的评分与是否含违约标签(中途跑单)。 */
    public record ReceivedReview(Instant occurredAt, double score, boolean breach) { }

    public record CreditHistory(List<Engagement> engagements, List<ReceivedReview> reviews,
                                 boolean penaltyRepaired) { }

    /**
     * @param now 显式传入,便于测试时间衰减(不在内部取当前时间)
     */
    public double calculate(CreditHistory history, Instant now) {
        if (history.engagements().isEmpty() && history.reviews().isEmpty()) {
            return NEW_USER_SCORE;
        }

        double fulfillment = fulfillmentRate(history.engagements(), now);
        double reviewAvg = reviewAverage(history.reviews(), fulfillment);
        double penalty = history.penaltyRepaired() ? 0.0 : breachPenalty(history.reviews(), now);

        double credit = 100 * (W_FULFILLMENT * fulfillment + W_REVIEW * reviewAvg + W_PENALTY * (1 - penalty));
        return Math.max(0.0, Math.min(100.0, credit));
    }

    /** Σ(完成单 × e^(−λt)) / Σ(接单 × e^(−λt))。 */
    private double fulfillmentRate(List<Engagement> engagements, Instant now) {
        if (engagements.isEmpty()) return 0.0;
        double completedWeight = 0.0;
        double totalWeight = 0.0;
        for (Engagement e : engagements) {
            double w = decay(e.occurredAt(), now);
            totalWeight += w;
            if (e.completed()) completedWeight += w;
        }
        return totalWeight == 0 ? 0.0 : completedWeight / totalWeight;
    }

    /** "工厂评工人"均值 / 5;无评价则以履约率兜底(§5.3.2)。 */
    private double reviewAverage(List<ReceivedReview> reviews, double fulfillmentFallback) {
        if (reviews.isEmpty()) return fulfillmentFallback;
        double sum = 0.0;
        for (ReceivedReview r : reviews) sum += r.score();
        return sum / reviews.size() / ReviewTag.MAX_SCORE;
    }

    /** 近 90 天失约次数的饱和函数 1 − e^(−0.7n)。 */
    private double breachPenalty(List<ReceivedReview> reviews, Instant now) {
        long n = reviews.stream()
                .filter(ReceivedReview::breach)
                .filter(r -> Duration.between(r.occurredAt(), now).toDays() <= RECENT_DAYS)
                .count();
        if (n == 0) return 0.0;
        return 1 - Math.exp(-PENALTY_K * n);
    }

    private static double decay(Instant occurredAt, Instant now) {
        double days = Math.max(0, Duration.between(occurredAt, now).toDays());
        return Math.exp(-LAMBDA * days);
    }

    /** 分段(§5.3.3)。本 Plan 只产出分段,不接押金——GuaranteePolicy 是 §8 的独立工作。 */
    public enum CreditTier {
        /** ≥80:免押,优先派单。免押比例是押金退出坡道的观测指标。 */
        EXEMPT,
        /** 60–79:半额押金。 */
        HALF,
        /** 40–59:全额押金。 */
        FULL,
        /** <40:全额押金 + 限制报名,需申诉/人工复核。 */
        RESTRICTED;

        public static CreditTier of(double score) {
            if (score >= 80) return EXEMPT;
            if (score >= 60) return HALF;
            if (score >= 40) return FULL;
            return RESTRICTED;
        }
    }

    /**
     * 修复机制(§5.3.4):连续完成 3 单且无差评 → 违约惩罚项清零一次。
     * "没有修复机制的信用体系是单向绞索:一次失约 → 永久低分 → 接不到活 →
     * 更没机会证明 → 流失。在蓝领场景这是致命的。"
     */
    public boolean qualifiesForRepair(List<Engagement> engagements, List<ReceivedReview> reviews) {
        List<Engagement> recent = engagements.stream()
                .sorted((a, b) -> b.occurredAt().compareTo(a.occurredAt()))
                .limit(3)
                .toList();
        if (recent.size() < 3 || !recent.stream().allMatch(Engagement::completed)) return false;
        Instant earliest = recent.get(recent.size() - 1).occurredAt();
        return reviews.stream()
                .filter(r -> !r.occurredAt().isBefore(earliest))
                .noneMatch(r -> r.score() < ReviewTag.MAX_SCORE);
    }
}
