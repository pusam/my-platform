package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 스케줄러 스레드 풀 설정 — 3개 풀 분리.
 *
 * 배경: 60+ @Scheduled 작업이 단일 풀(48)을 공유하면 장 마감 시각(15:50~16:45) 처럼
 * 크론이 몰리는 구간에 캐시 워밍/배치 작업이 트레이딩 사이클 스레드를 모두 점유 →
 * 매수/매도 사이클이 지연되는 사고가 가능. 풀을 분리하면 트레이딩 슬롯이 항상 확보된다.
 *
 * 풀:
 *  - taskScheduler  (16, @Primary) — 트레이딩(자동매매, 포지션 감시) + 풀 명시 안 한 작업의 기본값
 *  - cacheScheduler (16)            — 시세/수급/섹터 캐시 워밍업
 *  - batchScheduler (16)            — 크롤링, 일일 리포트, 정리 잡
 *
 * 사용법: @Scheduled(scheduler = "cacheScheduler", cron = ...) 처럼 명시.
 *          미명시 시 Spring 이 "taskScheduler" 빈을 default 로 자동 발견.
 *
 * 주의: 이전 버전은 SchedulingConfigurer 인터페이스에서 @Bean 메서드를 직접 호출(자기참조)했는데
 * Spring Boot 4.0 환경에서 부팅 실패 사례 — Primary @Bean 만 등록하는 구조로 단순화.
 */
@Configuration
@EnableScheduling
@Slf4j
public class SchedulingConfig {

    private static final int POOL_SIZE = 16;

    @Bean(name = "taskScheduler", destroyMethod = "shutdown")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler() {
        return build("scheduled-task-", POOL_SIZE, Thread.NORM_PRIORITY + 2);
    }

    @Bean(name = "cacheScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler cacheScheduler() {
        return build("cache-sched-", POOL_SIZE, Thread.NORM_PRIORITY);
    }

    @Bean(name = "batchScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler batchScheduler() {
        return build("batch-sched-", POOL_SIZE, Thread.NORM_PRIORITY - 1);
    }

    private ThreadPoolTaskScheduler build(String prefix, int size, int prio) {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(size);
        s.setThreadNamePrefix(prefix);
        s.setThreadPriority(prio);
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        s.setErrorHandler(t -> log.error("[{}] 스케줄 작업 오류: {}", prefix, t.getMessage(), t));
        s.setRejectedExecutionHandler((r, e) -> log.warn("[{}] 스케줄 작업 거부 — 큐 포화", prefix));
        s.initialize();
        log.info("스케줄러 풀 초기화: name={} size={} prio={}", prefix, size, prio);
        return s;
    }
}
