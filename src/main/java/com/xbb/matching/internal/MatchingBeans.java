package com.xbb.matching.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.random.RandomGenerator;

@Configuration
class MatchingBeans {

    @Bean
    MatchScorer matchScorer() {
        return new MatchScorer();
    }

    /**
     * ε-greedy 探索位要随机。抽成 bean 是为了让测试能注入固定种子——
     * 探索位的测试不能靠"跑一次看结果",那是 flaky 的根源。
     */
    /**
     * **不要用 `RandomGenerator.getDefault()`。** 它返回 L32X64MixRandom,
     * 由 `jdk.random` 模块提供——精简 JRE 镜像里没有这个模块,
     * 应用会在启动时直接崩:"No implementation of the random number
     * generator algorithm L32X64MixRandom is available"。
     *
     * <p>本地用完整 JDK 跑测试永远碰不到,是容器化时才暴露的。
     * `java.util.Random` 在 Java 17+ 实现了 RandomGenerator,且随处可用。
     */
    @Bean
    RandomGenerator matchingRandom() {
        return new java.util.Random();
    }
}
