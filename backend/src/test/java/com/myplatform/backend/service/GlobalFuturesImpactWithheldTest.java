package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * '종합 시장 방향성' — 해외 시세를 하나도 못 받으면 판단 보류(2026-10-03).
 *
 * <p>재현: 점수는 50(중립)에서 시작해 지표만큼 움직인다. 시세가 전부 실패하면 아무것도 더해지지 않아 50점·'보합 출발 예상'이
 * 그대로 나갔다 — 계산값이 아니라 시작값이다(§4c). 일부만 받았을 땐 몇 개로 계산했는지 밝힌다.
 */
class GlobalFuturesImpactWithheldTest {

    private static GlobalFuturesService.FuturesQuote ok(String symbol, String rate, String price) {
        return GlobalFuturesService.FuturesQuote.builder()
                .symbol(symbol).success(true)
                .changeRate(new BigDecimal(rate)).currentPrice(new BigDecimal(price))
                .build();
    }

    private static GlobalFuturesService.FuturesQuote failed(String symbol) {
        return GlobalFuturesService.FuturesQuote.builder().symbol(symbol).success(false).errorMessage("조회 실패").build();
    }

    private static GlobalFuturesService service(RestTemplate rt) {
        return new GlobalFuturesService(rt, new ObjectMapper(), mock(KisApiService.class));
    }

    @Test
    @DisplayName("재현: 해외 시세 조회가 전부 실패하면 50점·'보합 출발 예상'이 아니라 판단 보류")
    @SuppressWarnings("unchecked")
    void allQuotesFailedIsWithheld() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("network down"));

        Map<String, Object> a = service(rt).getKospiImpactAnalysis();

        assertThat(a.get("alertLevel")).isEqualTo("UNKNOWN");
        assertThat(a.get("impactScore")).isNull();
        assertThat(a.get("impact")).isNull();
        assertThat((String) a.get("comment")).contains("판단 보류").doesNotContain("보합 출발");
        assertThat(a.get("factorCount")).isEqualTo(0);
        // 공포·탐욕 지수 실패도 50·0 으로 채우지 않는다
        Map<String, Object> fg = (Map<String, Object>) a.get("fearGreed");
        assertThat(fg.get("success")).isEqualTo(false);
        assertThat(fg.get("score")).isNull();
        assertThat(fg.get("change")).isNull();
    }

    @Test
    @DisplayName("일부 지표만 받았으면 계산하되 '지표 N/5개로 계산'을 붙인다")
    void partialFactorsAreDisclosed() {
        Map<String, Object> a = service(mock(RestTemplate.class)).analyzeImpact(List.of(
                ok("NQ", "1.0", "20000"), failed("ES"), failed("CL"), failed("KRW"), failed("VIX")));

        assertThat(a.get("impactScore")).isNotNull();
        assertThat(a.get("factorCount")).isEqualTo(1);
        assertThat((String) a.get("comment")).contains("지표 1/5개로 계산");
    }

    @Test
    @DisplayName("다섯 지표가 다 있으면 종전대로 — 덧붙임 없음")
    void allFactorsUnchanged() {
        Map<String, Object> a = service(mock(RestTemplate.class)).analyzeImpact(List.of(
                ok("NQ", "0.0", "20000"), ok("ES", "0.0", "6000"), ok("CL", "0.0", "70"),
                ok("KRW", "0.0", "1350"), ok("VIX", "0.0", "17")));

        assertThat(a.get("alertLevel")).isEqualTo("NEUTRAL");
        assertThat(a.get("impactScore")).isEqualTo(50);
        assertThat(a.get("factorCount")).isEqualTo(5);
        assertThat((String) a.get("comment")).doesNotContain("개로 계산");
    }
}
