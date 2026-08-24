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
     * - 코어 풀: 3개 (순차 batch 조회이므로 많은 스레드 불필요)
     * - 최대 풀: 5개 (피크 시 확장)
     * - 큐 용량: 50개 (실제 동시 작업 수 고려)
     */
    @Bean(name = "sectorTradingExecutor")
    public ThreadPoolTaskExecutor sectorTradingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("SectorTrading-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        // 큐가 가득 찼을 때 호출 스레드에서 직접 실행 (CallerRunsPolicy)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        log.info("섹터 거래대금 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 50);

        return executor;
    }

    /**
     * 범용 비동기 작업 Executor
     * - 한정자 없는 @Async 의 기본 풀 (AsyncExecutorSelection 이 명시적으로 지정)
     *
     * 크기(2026-08-24): core 3→4, max 6→12. 이 풀은 그동안 실제로 쓰인 적이 없다 —
     * @Primary taskScheduler 가 TaskExecutor 타입 해석을 가로채 한정자 없는 @Async 가
     * 전부 트레이딩 풀(16스레드)에서 돌았기 때문이다. 이제 이쪽으로 오게 되므로 6 으로 두면
     * 기동 시 동시에 뜨는 워밍업들(캐시·시세·corpCode·KRX 시드)이 예전보다 좁은 목을 지난다.
     * 트레이딩과 분리하는 게 목적이지 조이는 게 아니라서 기존 가용폭(16)에 가깝게 잡는다.
     * 실측으로 검증된 수치는 아니다 — 기동이 느려지면 조정할 것.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("Async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("범용 비동기 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 100);

        return executor;
    }

    /**
     * 종목 상세 조회용 I/O Executor
     * - 종목 상세 페이지 로드 시 8~10개 병렬 외부 API 호출(KIS/Naver/FnGuide/Gemini)
     * - ForkJoinPool.commonPool() 대신 전용 풀 사용 → 공용 풀 고갈 방지
     */
    @Bean(name = "stockDetailExecutor")
    public Executor stockDetailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("StockDetail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        log.info("종목 상세 Executor 초기화 완료 - core: {}, max: {}, queue: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), 50);

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
