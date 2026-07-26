package com.xbb;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 退避是纯函数,纯 JUnit 单测。 */
class OutboxBackoffTest {

    private static final Duration BASE = Duration.ofSeconds(5);

    @Test
    void 第一次失败后等基础时长() {
        assertThat(OutboxBackoff.delayAfter(1, BASE)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void 之后逐次翻倍() {
        assertThat(OutboxBackoff.delayAfter(2, BASE)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OutboxBackoff.delayAfter(3, BASE)).isEqualTo(Duration.ofSeconds(20));
        assertThat(OutboxBackoff.delayAfter(4, BASE)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void 封顶在五分钟() {
        assertThat(OutboxBackoff.delayAfter(10, BASE)).isEqualTo(OutboxBackoff.MAX_DELAY);
        // 次数再大也不该溢出成负数或很小的值
        assertThat(OutboxBackoff.delayAfter(1000, BASE)).isEqualTo(OutboxBackoff.MAX_DELAY);
        assertThat(OutboxBackoff.delayAfter(Integer.MAX_VALUE, BASE)).isEqualTo(OutboxBackoff.MAX_DELAY);
    }

    @Test
    void 基础时长为零表示不退避() {
        // 测试环境用这个:关心的是"能重试",不是"等多久"
        assertThat(OutboxBackoff.delayAfter(3, Duration.ZERO)).isZero();
    }

    @Test
    void 还没失败过就不需要等() {
        assertThat(OutboxBackoff.delayAfter(0, BASE)).isZero();
    }
}
