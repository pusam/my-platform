package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 스케줄러 스레드 풀 설정
 * - 기본 단일 스레드 대신 멀티 스레드 풀 사용
 * - 여러 @Scheduled 작업이 동시에 실행 가능
 */
@Configuration
@EnableScheduling
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    private static final int POOL_SIZE = 10;
    private static final String THREAD_NAME_PREFIX = "scheduled-task-";

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setErrorHandler(throwable -> {
            log.error("스케줄 작업 실행 중 오류 발생: {}", throwable.getMessage(), throwable);
        });
        scheduler.setRejectedExecutionHandler((runnable, executor) -> {
            log.warn("스케줄 작업이 거부되었습니다. 큐가 가득 찼습니다.");
        });
        scheduler.initialize();

        log.info("스케줄러 ThreadPool 초기화 완료 - poolSize: {}, prefix: {}", POOL_SIZE, THREAD_NAME_PREFIX);
        return scheduler;
    }
}
