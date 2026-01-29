package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * - 텔레그램 알림 전용 Executor
 * - 섹터 거래대금 조회 전용 Executor
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * 알림 발송용 비동기 Executor
     * - 코어 풀: 2개 (평소 유지)
     * - 최대 풀: 5개 (피크 시 확장)
     * - 큐 용량: 100개 (대기열)
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("Notification-");
        executor.setRejectedExecutionHandler((r, e) ->
            log.warn("알림 발송 큐가 가득 찼습니다. 알림이 드랍됩니다.")
        );
        executor.initialize();

        log.info("알림용 비동기 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 100);

        return executor;
    }

    /**
     * 섹터 거래대금 조회용 Executor
     * - 코어 풀: 10개 (다수 종목 병렬 조회)
     * - 최대 풀: 20개 (피크 시 확장)
     * - 큐 용량: 200개 (종목 수 고려)
     * - Keep-Alive: 60초 (유휴 스레드 정리)
     *
     * [개선 2] Spring 관리 스레드 풀로 변경
     * - 모니터링 가능 (Actuator)
     * - Graceful Shutdown 지원
     * - 설정 변경 용이
     */
    @Bean(name = "sectorTradingExecutor")
    public ThreadPoolTaskExecutor sectorTradingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("SectorTrading-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // 큐가 가득 찼을 때 호출 스레드에서 직접 실행 (CallerRunsPolicy)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        log.info("섹터 거래대금 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 200);

        return executor;
    }

    /**
     * 범용 비동기 작업 Executor
     * - 기본 @Async 어노테이션에서 사용
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("범용 비동기 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 500);

        return executor;
    }

    /**
     * 크롤러용 비동기 Executor
     * - 영업이익률 크롤링, 분기별 재무제표 수집 등
     * - 코어 풀: 2개 (동시 크롤링 작업 제한)
     * - 최대 풀: 3개 (리소스 제한 고려)
     * - 큐 용량: 10개 (작업 대기열)
     * - 장시간 작업이므로 Keep-Alive 긴 설정
     */
    @Bean(name = "crawlerExecutor")
    public Executor crawlerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(10);
        executor.setKeepAliveSeconds(300);
        executor.setThreadNamePrefix("Crawler-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // 큐가 가득 찼을 때 거부 (이미 작업 중이면 새 작업 시작 안함)
        executor.setRejectedExecutionHandler((r, e) ->
            log.warn("크롤링 작업 큐가 가득 찼습니다. 현재 진행 중인 작업이 완료될 때까지 기다려주세요.")
        );

        executor.initialize();

        log.info("크롤러용 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 10);

        return executor;
    }
}
