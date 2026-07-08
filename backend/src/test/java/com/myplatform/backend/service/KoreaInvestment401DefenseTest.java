package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * KIS API 호출 401(인증 실패) 방어 — 토큰 캐시 1회 무효화 → 다음 getAccessToken 재발급.
 * 시계 기준 갱신(만료 1시간 전)만으로는 조기 만료를 못 잡는 사각 보완.
 * ⚠ §4d: 무효화까지만 — 주문 재시도는 없음(비멱등). 무효화는 1회성(발급 rate 보호).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaInvestment401DefenseTest {

    @Mock private RestTemplate restTemplate;
    @Mock private KisApiRateLimiter rateLimiter;

    private KoreaInvestmentService service;

    @BeforeEach
    void setUp() {
        service = new KoreaInvestmentService(restTemplate, new ObjectMapper(), rateLimiter);
        // rateLimiter 는 supplier 를 그대로 실행 (2-arg 경로: execute(priority, supplier))
        when(rateLimiter.execute(any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        ReflectionTestUtils.setField(service, "appKey", "k");
        ReflectionTestUtils.setField(service, "appSecret", "s");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mock.kis");
        // 유효 캐시 토큰 — getAccessToken 이 HTTP 재발급 없이 캐시 반환
        ReflectionTestUtils.setField(service, "accessToken", "cached-token");
        ReflectionTestUtils.setField(service, "tokenExpireTime", LocalDateTime.now().plusHours(12));
    }

    private Object token() {
        return ReflectionTestUtils.getField(service, "accessToken");
    }

    // ---- isAuthFailure 순수 판정 ----

    @Test
    @DisplayName("isAuthFailure: 401 → true, 403/429/5xx/IO → false")
    void isAuthFailure_classification() {
        assertThat(KoreaInvestmentService.isAuthFailure(
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED))).isTrue();
        assertThat(KoreaInvestmentService.isAuthFailure(
                new HttpClientErrorException(HttpStatus.FORBIDDEN))).isFalse();
        assertThat(KoreaInvestmentService.isAuthFailure(
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isFalse();
        assertThat(KoreaInvestmentService.isAuthFailure(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))).isFalse();
        assertThat(KoreaInvestmentService.isAuthFailure(
                new ResourceAccessException("timeout"))).isFalse();
    }

    // ---- 조회 API 401 → 토큰 무효화 ----

    @Test
    @DisplayName("조회 API 401 → 토큰 캐시 무효화(null) + null 반환")
    void queryApi_401_invalidatesToken() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        JsonNode result = service.getStockInfo("005930");

        assertThat(result).isNull();
        assertThat(token()).isNull();   // 401 → 무효화됨 → 다음 getAccessToken 이 재발급
    }

    @Test
    @DisplayName("조회 API 403(권한/IP) → 토큰 캐시 유지 (401 아님)")
    void queryApi_403_keepsToken() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        service.getStockInfo("005930");

        assertThat(token()).isEqualTo("cached-token");   // 401 아니면 무효화 안 함
    }

    // ---- 1회성 가드 (발급 rate 보호) ----

    @Test
    @DisplayName("무효화는 1회성 — 이미 null 이면 재무효화 no-op (발급 rate 폭주 방지)")
    void invalidation_isOneShot() {
        HttpClientErrorException unauthorized = new HttpClientErrorException(HttpStatus.UNAUTHORIZED);

        // 1차 401 → 무효화
        ReflectionTestUtils.invokeMethod(service, "invalidateTokenOnAuthFailure", unauthorized);
        assertThat(token()).isNull();

        // 캐시가 null 인 상태에서 2차 401 → no-op (예외 없이 그대로 null 유지, 재발급 시도 안 함)
        ReflectionTestUtils.invokeMethod(service, "invalidateTokenOnAuthFailure", unauthorized);
        assertThat(token()).isNull();
    }

    @Test
    @DisplayName("비-401 예외는 토큰 캐시 무접촉")
    void nonAuthException_noInvalidation() {
        ReflectionTestUtils.invokeMethod(service, "invalidateTokenOnAuthFailure",
                new ResourceAccessException("connect timeout"));
        assertThat(token()).isEqualTo("cached-token");
    }
}
