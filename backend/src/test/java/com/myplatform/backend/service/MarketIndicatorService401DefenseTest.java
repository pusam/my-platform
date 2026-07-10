package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
import com.myplatform.backend.repository.MarketIndicatorSnapshotRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarketIndicatorService 401(인증 실패) 방어 — P3-8 이식({@code KoreaInvestment401DefenseTest} 패턴 미러).
 * 장중 워머(refreshPriceMoversFromKis) 경유 401 → 자기 토큰 캐시 1회 무효화, 실패한 호출 재시도 없음
 * (다음 워머 주기가 재발급 후 재시도). 판정은 KoreaInvestmentService.isAuthFailure 재사용(단일 출처).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketIndicatorService401DefenseTest {

    @Mock private RestTemplate restTemplate;
    @Mock private MarketIndicatorSnapshotRepository snapshotRepository;
    @Mock private RedisCacheService redisCacheService;

    private MarketIndicatorService service;

    @BeforeEach
    void setUp() {
        KisApiProperties props = new KisApiProperties();
        props.setAppKey("k");
        props.setAppSecret("s");
        props.setBaseUrl("https://mock.kis");
        service = new MarketIndicatorService(restTemplate, new ObjectMapper(), props,
                snapshotRepository, redisCacheService);
        // 유효 캐시 토큰 — refreshAccessToken 이 HTTP 재발급 없이 캐시 유지
        ReflectionTestUtils.setField(service, "accessToken", "cached-token");
        ReflectionTestUtils.setField(service, "tokenExpireTime", System.currentTimeMillis() + 3_600_000L);
    }

    private Object token() {
        return ReflectionTestUtils.getField(service, "accessToken");
    }

    private void stubRankingGet(HttpStatus errorStatus) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(errorStatus));
    }

    @Test
    @DisplayName("워머 401 → 토큰 캐시 무효화 + Redis 미갱신(빈 결과는 put 안 함), 워머 예외 미전파")
    void warmer_401_invalidatesToken() {
        stubRankingGet(HttpStatus.UNAUTHORIZED);

        service.refreshPriceMoversFromKis();

        assertThat(token()).isNull();   // 401 → 무효화 → 다음 주기 refreshAccessToken 재발급
        verify(redisCacheService, never()).put(anyString(), anyString(), any(), any(Duration.class));
        // 상승/하락 각 1회씩(설계된 2 fetch) — 401 이 fetch 재시도 루프를 만들지 않는다
        verify(restTemplate, times(2))
                .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("워머 403(권한/IP) → 토큰 캐시 유지 (401 아님)")
    void warmer_403_keepsToken() {
        stubRankingGet(HttpStatus.FORBIDDEN);

        service.refreshPriceMoversFromKis();

        assertThat(token()).isEqualTo("cached-token");
    }

    @Test
    @DisplayName("401 후 다음 워머 주기는 재발급 1회, 2연속 401 은 실패 전파(무한루프 금지)")
    void consecutive401_reissuesOnceNoLoop() {
        stubRankingGet(HttpStatus.UNAUTHORIZED);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"new-token\",\"expires_in\":86400}"));

        // 1주기: 캐시 토큰 → 401 → 무효화(재발급 없음)
        service.refreshPriceMoversFromKis();
        assertThat(token()).isNull();
        verify(restTemplate, times(0))
                .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));

        // 2주기: 재발급 1회 → 다시 401 → 재무효화(그대로 실패, 루프 없음)
        service.refreshPriceMoversFromKis();
        verify(restTemplate, times(1))
                .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        assertThat(token()).isNull();
        verify(redisCacheService, never()).put(anyString(), anyString(), any(), any(Duration.class));
    }
}
