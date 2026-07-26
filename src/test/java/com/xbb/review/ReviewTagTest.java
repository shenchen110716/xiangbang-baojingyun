package com.xbb.review;

import com.xbb.review.internal.ReviewTag;
import com.xbb.review.internal.ReviewTag.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 折算规则是纯函数,纯 JUnit 单测,不起 Spring。表格全部照主文档 §5.3.1。 */
class ReviewTagTest {

    private static final double EPS = 1e-9;

    @Test
    void 全正面无负面得满分5() {
        double score = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("准时到岗", "手脚麻利"));

        assertThat(score).isCloseTo(5.0, within(EPS));
    }

    @Test
    void 一个迟到早退轻度扣05得45() {
        double score = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("迟到早退"));

        assertThat(score).isCloseTo(4.5, within(EPS));
    }

    @Test
    void 一个中途跑单重度扣25得25() {
        double score = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("中途跑单"));

        assertThat(score).isCloseTo(2.5, within(EPS));
    }

    @Test
    void 一个拖欠工资重度扣25得25() {
        double score = ReviewTag.score(Direction.WORKER_RATES_ORG, List.of("拖欠工资"));

        assertThat(score).isCloseTo(2.5, within(EPS));
    }

    @Test
    void 多个负面标签叠加扣分() {
        // 迟到早退(0.5) + 消极怠工(1.0) = 1.5,5 - 1.5 = 3.5
        double score = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("迟到早退", "消极怠工"));

        assertThat(score).isCloseTo(3.5, within(EPS));
    }

    @Test
    void 正面标签不抵消负面扣分() {
        double withPositive = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("准时到岗", "中途跑单"));
        double withoutPositive = ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("中途跑单"));

        assertThat(withPositive).isCloseTo(withoutPositive, within(EPS));
    }

    @Test
    void 扣到下限1不会变成负数() {
        // 中途跑单(2.5) + 消极怠工(1.0) + 不服管理(1.0) + 技能不符(1.0) = 5.5 > 5
        double score = ReviewTag.score(Direction.ORG_RATES_WORKER,
                List.of("中途跑单", "消极怠工", "不服管理", "技能不符"));

        assertThat(score).isCloseTo(1.0, within(EPS));
    }

    @Test
    void 工厂不能用工人侧的标签评工人() {
        assertThatThrownBy(() -> ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("拖欠工资")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该评价方向");
    }

    @Test
    void 工人不能用工厂侧的标签评工厂() {
        assertThatThrownBy(() -> ReviewTag.score(Direction.WORKER_RATES_ORG, List.of("中途跑单")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该评价方向");
    }

    @Test
    void 编造的标签直接报错() {
        assertThatThrownBy(() -> ReviewTag.score(Direction.ORG_RATES_WORKER, List.of("长得好看")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 没有标签时视为满分() {
        assertThat(ReviewTag.score(Direction.ORG_RATES_WORKER, List.of())).isCloseTo(5.0, within(EPS));
    }

    @Test
    void 中途跑单被识别为负面标签供信用分违约惩罚项使用() {
        assertThat(ReviewTag.isNegative(Direction.ORG_RATES_WORKER, ReviewTag.MID_QUIT)).isTrue();
        assertThat(ReviewTag.isNegative(Direction.ORG_RATES_WORKER, "准时到岗")).isFalse();
    }
}
