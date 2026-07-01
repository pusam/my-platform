package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini 전역 rate limiter — 무료 티어(≈15 RPM) 버스트 차단 회귀 테스트.
 *
 * <p>이전 enforceRateLimit 은 volatile check-then-act 라 동시 스레드가 같이 통과(버스트) → 429.
 * RateLimiter.acquire() 는 synchronized 라 FIFO 직렬화 + 최소 간격 유지.
 */
class GeminiRateLimiterTest {

    @Test
    @DisplayName("computeWaitMs — 첫 호출 0, 간격 미달 시 잔여 대기, 초과 시 0 (순수)")
    void computeWaitMs_cases() {
        assertThat(GeminiService.RateLimiter.computeWaitMs(0L, 5000L, 4500L)).isZero();        // 첫 호출(last=0)
        assertThat(GeminiService.RateLimiter.computeWaitMs(1000L, 1000L, 4500L)).isEqualTo(4500L);
        assertThat(GeminiService.RateLimiter.computeWaitMs(1000L, 3000L, 4500L)).isEqualTo(2500L);
        assertThat(GeminiService.RateLimiter.computeWaitMs(1000L, 6000L, 4500L)).isZero();      // 이미 간격 초과
    }

    @Test
    @DisplayName("acquire — 동시 4콜을 직렬화(버스트면 ~0ms, 직렬화면 ≥ (n-1)*간격)")
    void acquire_serializesConcurrentCallers() throws Exception {
        long intervalMs = 50;                      // 테스트용 짧은 간격
        int n = 4;
        GeminiService.RateLimiter rl = new GeminiService.RateLimiter(intervalMs);

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                startGate.await();                 // 동시에 출발
                rl.acquire();
                return null;
            }));
        }
        startGate.countDown();
        for (Future<Void> f : futures) f.get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;
        pool.shutdownNow();

        // 첫 콜 0 + 나머지 3콜 각 50ms 간격 = ≥150ms. 버스트(비직렬화)면 ~0ms 라 확실히 구분.
        assertThat(elapsed).isGreaterThanOrEqualTo((n - 1) * intervalMs - 10);
    }
}
