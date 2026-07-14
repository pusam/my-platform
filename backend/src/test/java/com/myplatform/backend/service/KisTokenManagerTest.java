package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 공유 KIS 토큰 매니저(P3-8 선택B) — 발급 직렬화·CAS 무효화·1회성·쿨다운·만료 갱신 단위 검증.
 * 3서비스는 이 매니저에 위임하므로 여기서 토큰 생명주기 불변식을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisTokenManagerTest {

    @Mock private RestTemplate restTemplate;

    private KisTokenManager manager;

    @BeforeEach
    void setUp() {
        manager = new KisTokenManager(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(manager, "appKey", "k");
        ReflectionTestUtils.setField(manager, "appSecret", "s");
        ReflectionTestUtils.setField(manager, "baseUrl", "https://mock.kis");
    }

    private Object token() {
        return ReflectionTestUtils.getField(manager, "accessToken");
    }

    private void primeValidToken(String value) {
        ReflectionTestUtils.setField(manager, "accessToken", value);
        ReflectionTestUtils.setField(manager, "tokenExpireTime", LocalDateTime.now().plusHours(12));
    }

    private void stubIssue(String tokenValue) {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(
                        "{\"access_token\":\"" + tokenValue + "\",\"expires_in\":86400}"));
    }

    // ---- 발급/캐시 ----

    @Test
    @DisplayName("유효 캐시 토큰이 있으면 발급 POST 없이 캐시 반환")
    void validCache_noIssue() {
        primeValidToken("cached");
        assertThat(manager.getAccessToken()).isEqualTo("cached");
        verify(restTemplate, times(0)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    @DisplayName("동시 발급 요청(N스레드) → 발급 POST 정확히 1회(synchronized 직렬화)")
    void concurrentIssue_singleApiCall() throws Exception {
        stubIssue("issued");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    assertThat(manager.getAccessToken()).isEqualTo("issued");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 첫 스레드만 발급, 나머지는 유효 캐시 재사용 → POST 1회.
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    // ---- 401 무효화: CAS(세대/버전 비교) ----

    @Test
    @DisplayName("401 무효화 — 사용 토큰이 현재 캐시와 동일하면 무효화(null)")
    void invalidate_currentToken_clears() {
        primeValidToken("AAA");
        manager.invalidateOnAuthFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED), "AAA");
        assertThat(token()).isNull();
    }

    @Test
    @DisplayName("401 무효화 — stale 참조(이미 재발급된 새 토큰)는 no-op(CAS): 방금 재발급된 토큰을 안 죽인다")
    void invalidate_staleReference_noop() {
        primeValidToken("BBB");   // 다른 스레드/서비스가 이미 재발급한 새 토큰
        // 옛 토큰 "AAA" 로 401 이 뒤늦게 도착 — 현재 캐시("BBB")와 불일치 → 무효화 스킵
        manager.invalidateOnAuthFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED), "AAA");
        assertThat(token()).isEqualTo("BBB");
    }

    @Test
    @DisplayName("401 무효화 — 이미 무효화(null)면 no-op(1회성, 발급 rate 보호)")
    void invalidate_alreadyNull_noop() {
        ReflectionTestUtils.setField(manager, "accessToken", null);
        manager.invalidateOnAuthFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED), "AAA");
        assertThat(token()).isNull();   // 예외 없이 no-op
    }

    @Test
    @DisplayName("무효화 — 비-401(403/IO)은 토큰 캐시 무접촉")
    void invalidate_nonAuth_keepsToken() {
        primeValidToken("AAA");
        manager.invalidateOnAuthFailure(new HttpClientErrorException(HttpStatus.FORBIDDEN), "AAA");
        assertThat(token()).isEqualTo("AAA");
        manager.invalidateOnAuthFailure(new ResourceAccessException("timeout"), "AAA");
        assertThat(token()).isEqualTo("AAA");
    }

    @Test
    @DisplayName("무효화 — usedToken null 이면 비교 불가로 무효화 안 함")
    void invalidate_nullUsedToken_noop() {
        primeValidToken("AAA");
        manager.invalidateOnAuthFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED), null);
        assertThat(token()).isEqualTo("AAA");
    }

    // ---- 쿨다운 ----

    @Test
    @DisplayName("발급 실패(HTTP 오류) → 쿨다운 설정 → 다음 getAccessToken 은 재발급 없이 null(쿨다운 준수)")
    void issueFailure_setsCooldown_respected() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        // 1차: 발급 시도 → 실패 → 쿨다운 설정 → null
        assertThat(manager.getAccessToken()).isNull();
        // 2차: 쿨다운 중 → 발급 POST 없이 null
        assertThat(manager.getAccessToken()).isNull();

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    // ---- 만료 갱신 ----

    @Test
    @DisplayName("만료 1시간 이내로 임박한 캐시 토큰 → 재발급(POST 1회, 새 토큰)")
    void nearExpiry_reissues() {
        ReflectionTestUtils.setField(manager, "accessToken", "stale");
        ReflectionTestUtils.setField(manager, "tokenExpireTime", LocalDateTime.now().plusMinutes(30));
        stubIssue("fresh");

        assertThat(manager.getAccessToken()).isEqualTo("fresh");
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    // ---- isTokenAvailable / isConfigured ----

    @Test
    @DisplayName("isTokenAvailable — 유효 캐시=true, 미설정=false")
    void isTokenAvailable_states() {
        primeValidToken("AAA");
        assertThat(manager.isTokenAvailable()).isTrue();

        ReflectionTestUtils.setField(manager, "appKey", "");   // 미설정
        ReflectionTestUtils.setField(manager, "accessToken", null);
        ReflectionTestUtils.setField(manager, "tokenExpireTime", null);
        assertThat(manager.isTokenAvailable()).isFalse();
        assertThat(manager.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("isTokenAvailable — 발급 POST 가 진행 중(락 점유)이어도 블로킹 없이 즉시 반환 (시세 경로 보호)")
    void isTokenAvailable_nonBlockingDuringIssue() throws Exception {
        // 발급 POST 를 latch 로 붙잡아 getAccessToken 이 synchronized 락을 쥔 채 대기하는 상황 재현
        CountDownLatch issueEntered = new CountDownLatch(1);
        CountDownLatch releaseIssue = new CountDownLatch(1);
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenAnswer(inv -> {
                    issueEntered.countDown();
                    releaseIssue.await(5, TimeUnit.SECONDS);
                    return ResponseEntity.ok("{\"access_token\":\"issued\",\"expires_in\":86400}");
                });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> manager.getAccessToken());   // 락 점유 + 발급 POST 블록
            assertThat(issueEntered.await(2, TimeUnit.SECONDS)).isTrue();

            // 발급이 진행 중인 동안 상태 조회는 락 대기 없이 즉시 반환해야 한다
            long startNs = System.nanoTime();
            boolean available = manager.isTokenAvailable();
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

            assertThat(available).isTrue();               // 키 설정됨 + 쿨다운 아님 → 발급 가능 상태
            assertThat(elapsedMs).isLessThan(500L);       // synchronized 였다면 releaseIssue 까지 수 초 대기
        } finally {
            releaseIssue.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("isAuthFailure — 401만 true, 403/429/5xx/IO false (단일 출처)")
    void isAuthFailure_classification() {
        assertThat(KisTokenManager.isAuthFailure(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))).isTrue();
        assertThat(KisTokenManager.isAuthFailure(new HttpClientErrorException(HttpStatus.FORBIDDEN))).isFalse();
        assertThat(KisTokenManager.isAuthFailure(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isFalse();
        assertThat(KisTokenManager.isAuthFailure(new ResourceAccessException("io"))).isFalse();
    }
}
