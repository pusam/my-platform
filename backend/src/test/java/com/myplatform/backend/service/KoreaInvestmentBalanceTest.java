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
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 잔고조회(getBalance) 실패 정직성 — rt_cd≠0 에러 바디를 "빈 정상잔고"로 위장하지 않는다.
 * 에러 바디가 그대로 반환되면 parseBalance 가 예수금 0·보유 없음 BalanceInfo 를 만들고 30초 캐시
 * → 정상 매도(손절)가 "보유 부족"으로 거부. rt_cd≠0 → null(실패 신호)이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaInvestmentBalanceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private KisApiRateLimiter rateLimiter;

    private KoreaInvestmentService service;

    @BeforeEach
    void setUp() {
        service = new KoreaInvestmentService(restTemplate, new ObjectMapper(), rateLimiter);
        // rateLimiter 는 supplier 를 그대로 실행 (직렬화 로직 무관)
        when(rateLimiter.execute(any(), any(), anyInt()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        // 토큰/계좌 설정 세팅 — 토큰 발급 HTTP 우회(캐시 유효), isRealTradingConfigured=true
        ReflectionTestUtils.setField(service, "appKey", "k");
        ReflectionTestUtils.setField(service, "appSecret", "s");
        ReflectionTestUtils.setField(service, "accountPrefix", "12345678");
        ReflectionTestUtils.setField(service, "accountSuffix", "01");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mock.kis");
        ReflectionTestUtils.setField(service, "accessToken", "cached-token");
        ReflectionTestUtils.setField(service, "tokenExpireTime", LocalDateTime.now().plusHours(12));
    }

    private void stubKisResponse(String body) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("rt_cd≠0(KIS 오류) → null — 에러 바디를 빈 정상잔고로 위장·캐시하지 않음")
    void balance_errorRtCd_returnsNull() {
        stubKisResponse("{\"rt_cd\":\"1\",\"msg1\":\"모의투자 장종료\",\"output1\":[],\"output2\":[]}");

        JsonNode result = service.getBalance();

        assertThat(result).isNull();   // 수정 전엔 에러 바디 JsonNode 반환 → parseBalance 가 빈 잔고 생성이 버그
    }

    @Test
    @DisplayName("rt_cd=0(정상) → 바디 그대로 반환 (기존 동작 불변)")
    void balance_success_returnsBody() {
        stubKisResponse("{\"rt_cd\":\"0\",\"output2\":[{\"dnca_tot_amt\":\"1000000\"}],\"output1\":[]}");

        JsonNode result = service.getBalance();

        assertThat(result).isNotNull();
        assertThat(result.get("rt_cd").asText()).isEqualTo("0");
        assertThat(result.get("output2").get(0).get("dnca_tot_amt").asText()).isEqualTo("1000000");
    }
}
