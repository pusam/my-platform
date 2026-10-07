package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * '복합 분석 지표'의 나스닥100·S&P500 이름표 — 보합권은 중립(2026-10-07 화면 점검).
 *
 * <p>재현: 글로벌 화면이 나스닥100 선물 '+0.02% 긍정'을 그렸다 — 판정이 기여도 부호(0 초과면 긍정)라 소수 둘째 자리에서
 * '+0.00%'로 보이는 움직임도 긍정·부정이 됐다. 같은 표의 WTI(±3%)·달러/원(±0.3%)은 보합권을 두고 있다.
 * 이름표만 바꾼다 — 점수(impactScore)는 그대로다.
 */
class GlobalFuturesIndexFactorBandTest {

    private static GlobalFuturesService.FuturesQuote ok(String symbol, String rate, String price) {
        return GlobalFuturesService.FuturesQuote.builder()
                .symbol(symbol).success(true)
                .changeRate(new BigDecimal(rate)).currentPrice(new BigDecimal(price))
                .build();
    }

    private static GlobalFuturesService service() {
        return new GlobalFuturesService(mock(RestTemplate.class), new ObjectMapper(), mock(KisApiService.class));
    }

    @SuppressWarnings("unchecked")
    private static String signalOf(Map<String, Object> analysis, String name) {
        return ((List<Map<String, Object>>) analysis.get("riskFactors")).stream()
                .filter(f -> name.equals(f.get("name")))
                .map(f -> (String) f.get("signal"))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("재현: 나스닥100 +0.02%·S&P500 −0.05% 는 보합권 — 긍정·부정이 아니라 중립")
    void tinyMovesAreNeutral() {
        Map<String, Object> a = service().analyzeImpact(List.of(
                ok("NQ", "0.02", "20000"), ok("ES", "-0.05", "6000"),
                ok("CL", "0.5", "70"), ok("KRW", "0.1", "1380"), ok("VIX", "16", "16")));

        assertThat(signalOf(a, "나스닥100")).isEqualTo("NEUTRAL");
        assertThat(signalOf(a, "S&P500")).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("보합권을 벗어나면 종전대로 방향을 말한다")
    void realMovesKeepDirection() {
        Map<String, Object> a = service().analyzeImpact(List.of(
                ok("NQ", "0.5", "20000"), ok("ES", "-0.3", "6000"),
                ok("CL", "0.5", "70"), ok("KRW", "0.1", "1380"), ok("VIX", "16", "16")));

        assertThat(signalOf(a, "나스닥100")).isEqualTo("POSITIVE");
        assertThat(signalOf(a, "S&P500")).isEqualTo("NEGATIVE");
    }

    @Test
    @DisplayName("경계: ±0.1% 부터 방향 — 0.1 은 긍정, −0.1 은 부정, 0.09 는 중립")
    void boundary() {
        assertThat(GlobalFuturesService.indexFactorSignal(0.1)).isEqualTo("POSITIVE");
        assertThat(GlobalFuturesService.indexFactorSignal(-0.1)).isEqualTo("NEGATIVE");
        assertThat(GlobalFuturesService.indexFactorSignal(0.09)).isEqualTo("NEUTRAL");
        assertThat(GlobalFuturesService.indexFactorSignal(0)).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("점수는 이름표와 무관 — 보합권 움직임도 종전과 같은 만큼 점수에 들어간다")
    void scoreUnchanged() {
        // NQ 0.09% → 기여 0.63×0.35 = 0.2205, ES 0.09% → 0.54×0.15 = 0.081 → 50.30 → 반올림 50
        // NQ 0.3% → 2.1×0.35 = 0.735 → 50.735 → 51 (점수 계산은 종전 그대로라는 확인)
        Map<String, Object> flat = service().analyzeImpact(List.of(ok("NQ", "0.09", "20000"), ok("ES", "0.09", "6000")));
        Map<String, Object> up = service().analyzeImpact(List.of(ok("NQ", "0.3", "20000")));

        assertThat(flat.get("impactScore")).isEqualTo(50);
        assertThat(up.get("impactScore")).isEqualTo(51);
    }
}
