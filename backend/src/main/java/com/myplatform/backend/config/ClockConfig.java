package com.myplatform.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * KST(Asia/Seoul) 기준 Clock 빈 — 시간 의존 로직을 테스트 가능하게 만들기 위해 주입.
 *
 * 운영에서는 system clock(Asia/Seoul) 그대로 동작하므로 기존 DateTimeUtil.kstNow() 와 동일한 결과.
 * 테스트에서는 Clock.fixed(...) 로 결정론적 시각 주입 가능.
 *
 * 사용처: AutoTradingBotService (kill switch / market hours 판정).
 */
@Configuration
public class ClockConfig {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock kstClock() {
        return Clock.system(KST);
    }
}
