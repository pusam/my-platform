package com.myplatform.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 처리 설정
 * - 텔레그램 알림 등 비동기 작업에 사용
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
}
