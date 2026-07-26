package com.xbb.voice.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class VoiceBeans {

    /**
     * 抽成 bean 是为了让撤回窗口能测:测试里换成固定/可推进的时钟,
     * 不用真的 sleep 五分钟。
     */
    @Bean
    Clock voiceClock() {
        return Clock.systemUTC();
    }
}
