package com.xbb;

import java.time.Duration;

/**
 * 投递失败后的重试退避。纯函数,不依赖 Spring。
 *
 * <p>为什么要退避:失败往往不是"这一瞬间不巧",而是下游挂了一段时间。
 * 不退避的话,一个坏掉的下游会被以中继的频率反复捶打;更糟的是取行按 id 升序、
 * 每批有上限,足够多的坏事件会把批次头部占满,后面正常的事件永远排不上。
 *
 * <p>指数退避但**设上限**:上限之外继续翻倍没有意义,只会让下游恢复后
 * 积压的事件迟迟不重投。到了上限就稳定按上限重试,直到有人处理。
 */
public final class OutboxBackoff {

    private OutboxBackoff() { }

    public static final Duration MAX_DELAY = Duration.ofMinutes(5);

    /**
     * 第 attemptCount 次失败之后,要等多久再试。
     *
     * @param attemptCount 已失败次数(从 1 开始)
     * @param baseDelay    第一次失败后的等待时长;测试里设 0 表示不退避
     */
    public static Duration delayAfter(int attemptCount, Duration baseDelay) {
        if (attemptCount <= 0 || baseDelay.isZero() || baseDelay.isNegative()) {
            return Duration.ZERO;
        }
        // 用 long 累乘并随时封顶,避免 attemptCount 很大时先溢出再封顶
        long millis = baseDelay.toMillis();
        for (int i = 1; i < attemptCount; i++) {
            millis *= 2;
            if (millis >= MAX_DELAY.toMillis()) {
                return MAX_DELAY;
            }
        }
        return Duration.ofMillis(Math.min(millis, MAX_DELAY.toMillis()));
    }
}
