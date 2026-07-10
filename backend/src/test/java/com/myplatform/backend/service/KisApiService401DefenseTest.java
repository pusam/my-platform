package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.config.KisApiProperties;
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

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KisApiService 401(인증 실패) 방어 — P3-8 이식({@code KoreaInvestment401DefenseTest} 패턴 미러).
 * 401 → 자기 토큰 캐시 1회 무효화(다음 호출 재발급). 실패한 호출 재시도 없음(무한루프 금지) —
 * 2연속 401 은 그대로 실패(빈 결과) 전파. 판정은 KoreaInvestmentService.isAuthFailure 재사용(단일 출처).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisApiService401DefenseTest {

    @Mock private RestTemplate restTemplate;

    private KisApiService service;

    @BeforeEach
    void setUp() {
        KisApiProperties props = new KisApiProperties();
        props.setAppKey("k");
        props.setAppSecret("s");
        props.setBaseUrl("https://mock.kis");
        service = new KisApiService(restTemplate, new ObjectMapper(), props);
        // 유효 캐시 토큰 — refreshAccessToken 이 HTTP 재발급 없이 캐시 유지
        ReflectionTestUtils.setField(service, "accessToken", "cached-token");
        ReflectionTestUtils.setField(service, "tokenExpireTime", System.currentTimeMillis() + 3_600_000L);
    }

    private Object token() {
        return ReflectionTestUtils.getField(service, "accessToken");
    }

    @Test
    @DisplayName("조회 API 401 → 토큰 캐시 무효화 + 빈 결과 반환(재시도 없음)")
    void queryApi_401_invalidatesToken() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThat(service.getInvestorTrend()).isEmpty();

        assertThat(token()).isNull();   // 401 → 무효화 → 다음 refreshAccessToken 이 재발급
        verify(restTemplate, times(1))
                .exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("조회 API 403(권한/IP) → 토큰 캐시 유지 (401 아님)")
    void queryApi_403_keepsToken() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        assertThat(service.getInvestorTrend()).isEmpty();
        assertThat(token()).isEqualTo("cached-token");
    }

    @Test
    @DisplayName("401 후 다음 호출은 재발급 1회, 2연속 401 은 실패 전파 — 호출당 API 시도 1회(무한루프 금지)")
    void consecutive401_reissuesOnceNoLoop() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"new-token\",\"expires_in\":86400}"));

        // 1차 호출: 캐시 토큰 사용 → 401 → 무효화(재발급/재시도 없이 빈 결과)
        assertThat(service.getInvestorTrend()).isEmpty();
        assertThat(token()).isNull();
        verify(restTemplate, times(0))
                .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));

        // 2차 호출: 무효화된 캐시 → 재발급 1회 → 다시 401 → 그대로 실패 전파(빈 결과)
        assertThat(service.getInvestorTrend()).isEmpty();
        verify(restTemplate, times(1))
                .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        // 호출당 조회 시도 정확히 1회 × 2호출 = 2회 — 401 이 재시도 루프를 만들지 않는다
        verify(restTemplate, times(2))
                .exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(token()).isNull();
    }
}
