package com.xbb.review;

import com.xbb.review.internal.CreditCalculator;
import com.xbb.review.internal.CreditCalculator.CreditHistory;
import com.xbb.review.internal.CreditCalculator.CreditTier;
import com.xbb.review.internal.CreditCalculator.Engagement;
import com.xbb.review.internal.CreditCalculator.ReceivedReview;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 信用分是纯函数,纯 JUnit 单测。时间显式传入,所以时间衰减这种"必须能测"的东西
 * 真的能测——不是靠等 90 天。
 */
class CreditCalculatorTest {

    private final CreditCalculator calculator = new CreditCalculator();

    /** 固定的"现在",不用 Instant.now(),测试结果才可复现。 */
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    private static Instant daysAgo(int days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    @Test
    void 新人没有任何记录时是60分不是0分() {
        double score = calculator.calculate(new CreditHistory(List.of(), List.of(), false), NOW);

        // "0 分等于把新人判死刑"(§5.3.2)
        assertThat(score).isEqualTo(CreditCalculator.NEW_USER_SCORE);
    }

    @Test
    void 全部完成且全好评拿高分() {
        CreditHistory history = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true), new Engagement(daysAgo(5), true)),
                List.of(new ReceivedReview(daysAgo(10), 5.0, false),
                        new ReceivedReview(daysAgo(5), 5.0, false)),
                false);

        double score = calculator.calculate(history, NOW);

        // 履约率 1.0 × 0.5 + 评价 1.0 × 0.3 + (1-0) × 0.2 = 1.0 → 100
        assertThat(score).isCloseTo(100.0, within(0.01));
    }

    @Test
    void 接了没干完拉低履约率() {
        CreditHistory allDone = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true), new Engagement(daysAgo(5), true)),
                List.of(), false);
        CreditHistory halfDone = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true), new Engagement(daysAgo(5), false)),
                List.of(), false);

        assertThat(calculator.calculate(halfDone, NOW)).isLessThan(calculator.calculate(allDone, NOW));
    }

    // ---------- 时间衰减是灵魂(§5.3.2) ----------

    @Test
    void 久远的失约比最近的失约影响小() {
        CreditHistory recentBreach = new CreditHistory(
                List.of(new Engagement(daysAgo(3), false), new Engagement(daysAgo(2), true)),
                List.of(), false);
        CreditHistory oldBreach = new CreditHistory(
                List.of(new Engagement(daysAgo(300), false), new Engagement(daysAgo(2), true)),
                List.of(), false);

        // 半年前的失约与上周的失约权重必须不同,否则一次失误永久钉死 → 接不到活 → 流失
        assertThat(calculator.calculate(oldBreach, NOW)).isGreaterThan(calculator.calculate(recentBreach, NOW));
    }

    @Test
    void 半衰期90天时90天前的记录权重约为当下的一半() {
        // 一条 90 天前的完成单 + 一条当下的失约单:
        // 完成权重 = e^(-ln2) = 0.5,失约权重 = 1.0 → 履约率 ≈ 0.5/1.5 = 1/3
        CreditHistory history = new CreditHistory(
                List.of(new Engagement(daysAgo(90), true), new Engagement(NOW, false)),
                List.of(), false);

        double score = calculator.calculate(history, NOW);
        // 履约率(1/3) × 0.5 + 评价兜底(1/3) × 0.3 + 1 × 0.2 = 0.4667 → 46.67
        assertThat(score).isCloseTo(46.67, within(0.5));
    }

    // ---------- 违约惩罚饱和(§5.3.2:1 − e^(−0.7n)) ----------

    @Test
    void 违约惩罚随次数增长但饱和不超过1() {
        CreditHistory one = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true)),
                List.of(new ReceivedReview(daysAgo(10), 2.5, true)), false);
        CreditHistory five = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true)),
                List.of(new ReceivedReview(daysAgo(10), 2.5, true),
                        new ReceivedReview(daysAgo(9), 2.5, true),
                        new ReceivedReview(daysAgo(8), 2.5, true),
                        new ReceivedReview(daysAgo(7), 2.5, true),
                        new ReceivedReview(daysAgo(6), 2.5, true)), false);

        double oneScore = calculator.calculate(one, NOW);
        double fiveScore = calculator.calculate(five, NOW);

        assertThat(fiveScore).isLessThan(oneScore);
        // 饱和:惩罚项本身封顶在 1,总分不会为负
        assertThat(fiveScore).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void 超过90天的失约不再计入违约惩罚项() {
        CreditHistory recent = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true)),
                List.of(new ReceivedReview(daysAgo(10), 5.0, true)), false);
        CreditHistory old = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true)),
                List.of(new ReceivedReview(daysAgo(200), 5.0, true)), false);

        assertThat(calculator.calculate(old, NOW)).isGreaterThan(calculator.calculate(recent, NOW));
    }

    @Test
    void 无评价时以履约率兜底不会崩() {
        CreditHistory history = new CreditHistory(
                List.of(new Engagement(daysAgo(10), true)), List.of(), false);

        double score = calculator.calculate(history, NOW);

        // 履约率 1.0,评价维度用履约率兜底 → 1×0.5 + 1×0.3 + 1×0.2 = 100
        assertThat(score).isCloseTo(100.0, within(0.01));
    }

    @Test
    void 分数被夹在0到100之间() {
        CreditHistory awful = new CreditHistory(
                List.of(new Engagement(daysAgo(1), false)),
                List.of(new ReceivedReview(daysAgo(1), 1.0, true),
                        new ReceivedReview(daysAgo(1), 1.0, true),
                        new ReceivedReview(daysAgo(1), 1.0, true)), false);

        double score = calculator.calculate(awful, NOW);

        assertThat(score).isBetween(0.0, 100.0);
    }

    // ---------- 分段(§5.3.3) ----------

    @Test
    void 信用分段按主文档阈值划分() {
        assertThat(CreditTier.of(85)).isEqualTo(CreditTier.EXEMPT);
        assertThat(CreditTier.of(80)).isEqualTo(CreditTier.EXEMPT);
        assertThat(CreditTier.of(79)).isEqualTo(CreditTier.HALF);
        assertThat(CreditTier.of(60)).isEqualTo(CreditTier.HALF);
        assertThat(CreditTier.of(59)).isEqualTo(CreditTier.FULL);
        assertThat(CreditTier.of(40)).isEqualTo(CreditTier.FULL);
        assertThat(CreditTier.of(39)).isEqualTo(CreditTier.RESTRICTED);
    }

    @Test
    void 新人60分落在半额押金段而不是受限段() {
        assertThat(CreditTier.of(CreditCalculator.NEW_USER_SCORE)).isEqualTo(CreditTier.HALF);
    }

    // ---------- 修复机制(§5.3.4) ----------

    @Test
    void 连续完成3单且无差评可以修复违约惩罚() {
        List<Engagement> engagements = List.of(
                new Engagement(daysAgo(30), true),
                new Engagement(daysAgo(20), true),
                new Engagement(daysAgo(10), true));
        List<ReceivedReview> reviews = List.of(
                new ReceivedReview(daysAgo(30), 5.0, false),
                new ReceivedReview(daysAgo(10), 5.0, false));

        assertThat(calculator.qualifiesForRepair(engagements, reviews)).isTrue();
    }

    @Test
    void 最近3单里有没干完的不能修复() {
        List<Engagement> engagements = List.of(
                new Engagement(daysAgo(30), true),
                new Engagement(daysAgo(20), false),
                new Engagement(daysAgo(10), true));

        assertThat(calculator.qualifiesForRepair(engagements, List.of())).isFalse();
    }

    @Test
    void 期间有差评不能修复() {
        List<Engagement> engagements = List.of(
                new Engagement(daysAgo(30), true),
                new Engagement(daysAgo(20), true),
                new Engagement(daysAgo(10), true));
        List<ReceivedReview> reviews = List.of(new ReceivedReview(daysAgo(15), 4.5, false));

        assertThat(calculator.qualifiesForRepair(engagements, reviews)).isFalse();
    }

    @Test
    void 不足3单不能修复() {
        List<Engagement> engagements = List.of(
                new Engagement(daysAgo(20), true), new Engagement(daysAgo(10), true));

        assertThat(calculator.qualifiesForRepair(engagements, List.of())).isFalse();
    }

    @Test
    void 修复后违约惩罚项清零分数回升() {
        List<Engagement> engagements = List.of(new Engagement(daysAgo(10), true));
        List<ReceivedReview> reviews = List.of(new ReceivedReview(daysAgo(10), 5.0, true));

        double before = calculator.calculate(new CreditHistory(engagements, reviews, false), NOW);
        double after = calculator.calculate(new CreditHistory(engagements, reviews, true), NOW);

        assertThat(after).isGreaterThan(before);
    }
}
