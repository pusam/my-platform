package com.myplatform.backend.aspect;

import com.myplatform.backend.entity.BatchJobExecution;
import com.myplatform.backend.repository.BatchJobExecutionRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Aspect
@Component
public class ScheduledJobMonitorAspect {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobMonitorAspect.class);

    private final BatchJobExecutionRepository repository;

    public ScheduledJobMonitorAspect(BatchJobExecutionRepository repository) {
        this.repository = repository;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object monitorScheduledJob(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();

        // 자기 자신(BatchJobCleanupService)의 cleanup은 무한루프 방지를 위해 모니터링 제외
        if ("cleanOldExecutions".equals(methodName) && className.contains("BatchJobCleanup")) {
            return joinPoint.proceed();
        }

        // 고빈도 스케줄러(3~5초 간격)는 DB 부하 방지를 위해 모니터링 제외
        // 스캘핑 매수/매도, 캐시 워머 등
        if (className.contains("AutoTradingBot") || className.contains("MarketCacheWarmer")) {
            return joinPoint.proceed();
        }

        // 헬스체크 자기 자신 제외 (자신을 "행 의심"으로 보고하는 문제 방지)
        if ("sendDailyHealthCheck".equals(methodName)) {
            return joinPoint.proceed();
        }

        String jobName = toReadableJobName(methodName);
        String jobClass = joinPoint.getTarget().getClass().getName();

        BatchJobExecution execution = BatchJobExecution.builder()
                .jobName(jobName)
                .jobClass(jobClass)
                .startedAt(LocalDateTime.now())
                .status("RUNNING")
                .build();

        try {
            execution = repository.save(execution);
        } catch (Exception e) {
            log.warn("배치 모니터링 기록 실패 (시작): {}", e.getMessage());
            return joinPoint.proceed();
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            execution.setFinishedAt(LocalDateTime.now());
            execution.setDurationMs(duration);
            execution.setStatus("SUCCESS");
            repository.save(execution);

            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - start;

            execution.setFinishedAt(LocalDateTime.now());
            execution.setDurationMs(duration);
            execution.setStatus("FAILED");
            execution.setErrorMessage(truncate(ex.getMessage(), 2000));
            repository.save(execution);

            throw ex;
        }
    }

    private String toReadableJobName(String methodName) {
        // camelCase → 읽기 쉬운 이름 (예: updateStockPrices → Update Stock Prices)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
