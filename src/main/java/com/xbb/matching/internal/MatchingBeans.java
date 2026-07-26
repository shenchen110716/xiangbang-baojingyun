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
    @Bean
    RandomGenerator matchingRandom() {
        return RandomGenerator.getDefault();
    }
}
