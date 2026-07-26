package com.xbb;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全局唯一的时钟 bean。
 *
 * <p>各域一开始各自定义了 {@code voiceClock}/{@code mallClock},结果不只是互相歧义——
 * **Spring Modulith 自己的 {@code Moments} 也按类型注入 Clock**,两个 bean 直接
 * 让整个上下文起不来。而且本来也没有任何理由让不同域看到不同的"现在"。
 *
 * <p>测试要控制时间时用 {@code @Primary} 覆盖它(见 VoiceSessionServiceTest)。
 */
@Configuration
public class SharedClockConfig {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
