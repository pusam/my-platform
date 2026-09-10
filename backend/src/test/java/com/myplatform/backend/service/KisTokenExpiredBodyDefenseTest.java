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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 만료 토큰 방어 — KIS 는 만료 토큰에 401 이 아니라 <b>HTTP 500 + EGW00123</b>("기간이 만료된 token")을 준다.
 *
 * <p>고치려는 결함(2026-09-08 실측): 401 방어({@code invalidateOnAuthFailure})가 401 만 보므로 EGW00123 은
 * 그냥 지나갔고, 로컬 만료 1시간 전 갱신창(발급+23h)이 열릴 때까지 08:00~08:20 20분간 죽은 토큰으로
 * 잔고·공시 모니터의 모든 KIS 호출이 실패했다. 바디의 msg_cd 로 판정을 확장한다 — 무효화까지만,
 * 주문 재시도는 여전히 없음(§4d 불변).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KisTokenExpiredBodyDefenseTest {

    private static final String EXPIRED_BODY =
            "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}";

    @Mock private RestTemplate restTemplate;
    @Mock private KisApiRateLimiter rateLimiter;

    private KisTokenManager tokenManager;
    private KoreaInvestmentService service;

    private static HttpServerErrorException expiredToken500() {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                null, EXPIRED_BODY.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        tokenManager = new KisTokenManager(restTemplate, new ObjectMapper(), null);
        ReflectionTestUtils.setField(tokenManager, "appKey", "k");
        ReflectionTestUtils.setField(tokenManager, "appSecret", "s");
        ReflectionTestUtils.setField(tokenManager, "baseUrl", "https://mock.kis");
        ReflectionTestUtils.setField(tokenManager, "accessToken", "stale-token");
        ReflectionTestUtils.setField(tokenManager, "tokenExpireTime", LocalDateTime.now().plusHours(12));

        service = new KoreaInvestmentService(restTemplate, new ObjectMapper(), rateLimiter, tokenManager);
        when(rateLimiter.execute(any(), any(), anyInt()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        ReflectionTestUtils.setField(service, "appKey", "k");
        ReflectionTestUtils.setField(service, "appSecret", "s");
        ReflectionTestUtils.setField(service, "accountPrefix", "12345678");
        ReflectionTestUtils.setField(service, "accountSuffix", "01");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mock.kis");
    }

    @Test
    @DisplayName("isAuthFailure: 500+EGW00123 → true, 바디 없는/다른 500 → false, 401 → true (기존 유지)")
    void expiredTokenBodyIsAuthFailure() {
        assertThat(KisTokenManager.isAuthFailure(expiredToken500())).isTrue();
        assertThat(KisTokenManager.isAuthFailure(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))).isFalse();
        assertThat(KisTokenManager.isAuthFailure(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "ISE", null,
                "{\"msg_cd\":\"EGW00215\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))).isFalse();
        assertThat(KisTokenManager.isAuthFailure(
                new HttpClientErrorException(HttpStatus.UNAUTHORIZED))).isTrue();
    }

    @Test
    @DisplayName("잔고 조회가 500+EGW00123 → 공유 토큰 캐시 무효화(다음 호출 재발급) — 수정 전엔 만료창까지 방치")
    void balanceCallWithExpiredTokenInvalidatesCache() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(expiredToken500());

        assertThatThrownBy(() -> service.getBalance()).isInstanceOf(RuntimeException.class);

        assertThat(ReflectionTestUtils.getField(tokenManager, "accessToken"))
                .as("죽은 토큰이 캐시에 남으면 다음 tick 도 같은 실패").isNull();
    }

    @Test
    @DisplayName("EGW00215(초당 한도)는 토큰을 건드리지 않는다 — 무효화는 만료 신호에만")
    void rateLimitBodyDoesNotInvalidate() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "ISE", null,
                        "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00215\",\"msg1\":\"초당 거래건수 초과\"}"
                                .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.getBalance()).isInstanceOf(RuntimeException.class);

        assertThat(ReflectionTestUtils.getField(tokenManager, "accessToken")).isEqualTo("stale-token");
    }
}
