package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 스케줄러 스레드 풀 설정 — 3개 풀로 분리.
 *
 * 배경: 60+ @Scheduled 작업이 단일 풀(32)을 공유하면, 장 마감 시각(15:50~16:45)
 * 처럼 크론이 몰리는 구간에 캐시 워밍/배치 작업이 트레이딩 사이클 스레드를
 * 모두 점유 → 매수/매도 사이클이 1분 이상 지연되는 사고가 가능. 풀을 분리하면
 * 트레이딩 슬롯은 항상 비어 있다.
 *
 * 풀:
 *  - tradingScheduler (8) — 자동매매 사이클, 포지션 감시, 킬스위치. @Scheduled 기본값.
 *  - cacheScheduler   (16) — 시세/수급/섹터 캐시 워밍업. 트래픽 보호용 배경 작업.
 *  - batchScheduler   (8) — 크롤링, 일일 리포트, 마이그레이션, 정리 잡.
 *
 * 사용법: @Scheduled(scheduler = "cacheScheduler", cron = ...) 처럼 명시.
 * 미명시 시 기본은 tradingScheduler (primary).
 */
@Configuration
@EnableScheduling
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    private static final int TRADING_POOL_SIZE = 8;
    private static final int CACHE_POOL_SIZE = 16;
    private static final int BATCH_POOL_SIZE = 8;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // 명시되지 않은 @Scheduled 작업은 tradingScheduler 로 위임 — 트레이딩 무관 작업은
        // 반드시 scheduler 속성으로 풀을 지정해야 한다.
        taskRegistrar.setTaskScheduler(tradingScheduler());
    }

    @Bean(name = "tradingScheduler", destroyMethod = "shutdown")
    @Primary
    public ThreadPoolTaskScheduler tradingScheduler() {
        return buildScheduler("trading-sched-", TRADING_POOL_SIZE, Thread.NORM_PRIORITY + 2);
    }

    @Bean(name = "cacheScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler cacheScheduler() {
        return buildScheduler("cache-sched-", CACHE_POOL_SIZE, Thread.NORM_PRIORITY);
    }

    @Bean(name = "batchScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler batchScheduler() {
        return buildScheduler("batch-sched-", BATCH_POOL_SIZE, Thread.NORM_PRIORITY - 1);
    }

    private ThreadPoolTaskScheduler buildScheduler(String prefix, int poolSize, int threadPriority) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setThreadPriority(threadPriority);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(t ->
                log.error("[{}] 스케줄 작업 오류: {}", prefix, t.getMessage(), t));
        scheduler.setRejectedExecutionHandler((runnable, executor) ->
                log.warn("[{}] 스케줄 작업 거부 — 큐 포화", prefix));
        scheduler.initialize();
        log.info("스케줄러 풀 초기화: name={} size={} prio={}", prefix, poolSize, threadPriority);
        return scheduler;
    }
}
