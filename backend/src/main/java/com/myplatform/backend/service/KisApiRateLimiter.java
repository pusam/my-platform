package com.myplatform.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * KIS API 중앙 Rate Limiter
 *
 * - 모든 KIS API 호출을 단일 지점에서 제어
 * - 요청 간 최소 간격(200ms) 보장 → 초당 5회 이내
 * - 우선순위 기반: CRITICAL(매매주문) > HIGH(실시간 시세) > NORMAL(스냅샷) > LOW(배경수집)
 * - 429 응답 시 지수 백오프 재시도
 * - LOW 우선순위 요청은 백프레셔 적용 (대기열 과다 시 드랍)
 */
@Component
@Slf4j
public class KisApiRateLimiter {

    // KIS API 제한: 초당 약 20회 (엔드포인트별 상이, 실전계좌는 더 빡빡함)
    // 안전 목표: 400ms 간격 = 초당 2.5회 — 실전 잔고/주문 + 다른 스케줄 호출 병렬성 고려
    private static final long MIN_INTERVAL_MS = 400;

    /**
     * API 호출 우선순위
     */
    public enum Priority {
        CRITICAL(0),    // 봇 매매 주문 (매수/매도)
        HIGH(1),        // 활성 포지션 실시간 시세
        NORMAL(2),      // 스케줄 스냅샷, 스크리너
        LOW(3);         // 배경 데이터 수집

        final int level;

        Priority(int level) {
            this.level = level;
        }
    }

    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final Semaphore apiSemaphore = new Semaphore(1); // 단일 스레드 API 접근
    private final AtomicInteger pendingRequests = new AtomicInteger(0);
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger throttledRequests = new AtomicInteger(0);
    private final AtomicInteger retryRequests = new AtomicInteger(0);
    private final AtomicInteger droppedRequests = new AtomicInteger(0);

    /**
     * Rate-limited API 호출 실행 (기본 2회 재시도)
     *
     * @param priority 우선순위
     * @param apiCall  실행할 API 호출
     * @return API 응답 (실패/드랍 시 null)
     */
    public <T> T execute(Priority priority, Supplier<T> apiCall) {
        return execute(priority, apiCall, 2);
    }

    /**
     * Rate-limited API 호출 실행
     *
     * @param priority   우선순위
     * @param apiCall    실행할 API 호출
     * @param maxRetries 최대 재시도 횟수
     * @return API 응답 (실패/드랍 시 null)
     */
    public <T> T execute(Priority priority, Supplier<T> apiCall, int maxRetries) {
        pendingRequests.incrementAndGet();
        try {
            // LOW 우선순위: 대기열이 10개 초과 시 백프레셔로 드랍
            if (priority == Priority.LOW && pendingRequests.get() > 10) {
                log.debug("[RateLimiter] LOW 우선순위 요청 드랍 (백프레셔: 대기 {}건)", pendingRequests.get());
                droppedRequests.incrementAndGet();
                return null;
            }

            int attempt = 0;
            while (attempt <= maxRetries) {
                try {
                    // Semaphore 획득 (CRITICAL은 30초, 나머지 10초 타임아웃)
                    long timeout = priority == Priority.CRITICAL ? 30000 : 10000;
                    if (!apiSemaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
                        log.warn("[RateLimiter] API 슬롯 대기 타임아웃 (priority={}, attempt={})", priority, attempt);
                        if (priority == Priority.CRITICAL) {
                            attempt++;
                            continue; // CRITICAL은 항상 재시도
                        }
                        throttledRequests.incrementAndGet();
                        return null;
                    }

                    try {
                        // 최소 간격 보장
                        enforceInterval();

                        // API 호출 실행
                        totalRequests.incrementAndGet();
                        T result = apiCall.get();
                        return result;
                    } finally {
                        apiSemaphore.release();
                    }
                } catch (Exception e) {
                    if (is429Error(e) || isRateLimitError(e)) {
                        attempt++;
                        retryRequests.incrementAndGet();
                        if (attempt <= maxRetries) {
                            long backoff = (long) (1000 * Math.pow(2, attempt)); // 2초, 4초
                            log.warn("[RateLimiter] 429 Rate Limited → {}ms 백오프 (attempt {}/{})",
                                    backoff, attempt, maxRetries);
                            Thread.sleep(backoff);
                        } else {
                            log.error("[RateLimiter] 최대 재시도 초과 (priority={}, retries={})", priority, maxRetries);
                        }
                    } else {
                        throw e;
                    }
                }
            }

            throttledRequests.incrementAndGet();
            return null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[RateLimiter] 인터럽트 발생 (priority={})", priority);
            return null;
        } finally {
            pendingRequests.decrementAndGet();
        }
    }

    /**
     * 요청 간 최소 간격 보장
     */
    private void enforceInterval() throws InterruptedException {
        long now = System.currentTimeMillis();
        long last = lastRequestTime.get();
        long elapsed = now - last;
        if (elapsed < MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - elapsed);
        }
        lastRequestTime.set(System.currentTimeMillis());
    }

    /**
     * HTTP 429 에러 여부 확인
     */
    private boolean is429Error(Exception e) {
        if (e instanceof org.springframework.web.client.HttpClientErrorException) {
            return ((org.springframework.web.client.HttpClientErrorException) e)
                    .getStatusCode().value() == 429;
        }
        return false;
    }

    /**
     * Rate Limit 관련 에러 메시지 확인.
     * KIS 는 rate limit 를 500 Internal Server Error 로 반환하고 본문에
     * "EGW00201" / "초당 거래건수를 초과하였습니다" 를 담는다 — 문자열로 감지.
     */
    private boolean isRateLimitError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        return msg.contains("Too Many Requests")
                || msg.contains("rate limit")
                || msg.contains("Rate limit")
                || msg.contains("EGW00201")
                || msg.contains("초당 거래건수")
                || msg.contains("초당거래건수");
    }

    /**
     * 모니터링용 통계 반환
     */
    public Map<String, Integer> getStats() {
        return Map.of(
                "pending", pendingRequests.get(),
                "total", totalRequests.get(),
                "throttled", throttledRequests.get(),
                "retried", retryRequests.get(),
                "dropped", droppedRequests.get()
        );
    }

    /**
     * 일일 통계 초기화
     */
    public void resetStats() {
        totalRequests.set(0);
        throttledRequests.set(0);
        retryRequests.set(0);
        droppedRequests.set(0);
    }
}
