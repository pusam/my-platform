package com.myplatform.backend.service;

import com.myplatform.backend.dto.MarketTimingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 시장 예측(KOSPI 5일) — 만들지 못한 예측을 숫자로 채우지 않는다(2026-10-03).
 *
 * <p>재현: Gemini 가 실패하면 현재 지수에 하루 ±0.5% 를 곱한 직선 세 개와 시나리오 확률 30/50/20·근거 문장
 * ("외국인 매수 유입 시 상승 가능")을 지어내 예측처럼 보였고, 그 결과를 10분 캐시해 'AI 재분석'도 같은 값을 돌려줬다.
 */
class GeminiForecastHonestyTest {

    @SuppressWarnings("unchecked")
    private static GeminiService service() {
        ObjectProvider<TelegramNotificationService> tg = mock(ObjectProvider.class);
        when(tg.getIfAvailable()).thenReturn(null);
        ObjectProvider<KoreaInvestmentService> kis = mock(ObjectProvider.class);
        when(kis.getIfAvailable()).thenReturn(null);
        GeminiService s = new GeminiService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), tg, kis);
        ReflectionTestUtils.setField(s, "apiKey", null);   // Gemini 응답 없음과 같은 경로(호출하지 않고 null)
        return s;
    }

    private static MarketTimingDto timing() {
        return MarketTimingDto.builder()
                .kospi(MarketTimingDto.MarketStatusDto.builder().indexClose(new BigDecimal("2750")).build())
                .build();
    }

    @Test
    @DisplayName("재현: Gemini 실패 시 확률·예측선을 만들지 않는다 — 예측 불가만 알린다")
    void unavailableForecastHasNoNumbers() {
        Map<String, Object> f = service().generateMarketForecast(timing(), List.of(), List.of(), List.of());

        assertThat(f.get("fallback")).isEqualTo(true);
        assertThat(f).doesNotContainKeys("scenarios", "forecasts");
        assertThat(f.get("summary").toString()).contains("만들지 않았습니다");
    }

    @Test
    @DisplayName("실패는 캐시하지 않는다 — 다음 요청이 다시 시도한다(예전엔 10분간 같은 가짜 예측)")
    void unavailableIsNotCached() {
        GeminiService s = service();
        s.generateMarketForecast(timing(), List.of(), List.of(), List.of());
        assertThat(ReflectionTestUtils.getField(s, "forecastCache")).isNull();
    }

    @Test
    @DisplayName("재현: ADR 판단 보류(시장 상태 null)를 'NORMAL'로 넘기지 않는다")
    void withheldConditionIsNotNormal() {
        assertThat(GeminiService.forecastConditionText(null)).doesNotContain("NORMAL").contains("미집계");
        assertThat(GeminiService.forecastConditionText(MarketTimingDto.MarketCondition.OVERHEATED)).isEqualTo("OVERHEATED");
    }

    @Test
    @DisplayName("재현: 헤드라인에 '[중립]'을 붙이지 않는다 — 수집기가 모든 기사를 NEUTRAL 로 저장해 분류 결과가 아니다")
    void headlinesCarryNoSentimentTag() {
        com.myplatform.backend.dto.NewsSummaryDto n = new com.myplatform.backend.dto.NewsSummaryDto();
        n.setTitle("코스피, 외국인 매도에 하락");
        n.setSentiment("NEUTRAL");
        n.setSentimentLabel("중립");

        String text = GeminiService.newsHeadlinesForForecast(List.of(n));

        assertThat(text).isEqualTo("- 코스피, 외국인 매도에 하락");
        assertThat(text).doesNotContain("중립");
        assertThat(GeminiService.newsHeadlinesForForecast(List.of())).isEqualTo("데이터 없음");
    }

    @Test
    @DisplayName("숫자가 없거나 숫자가 아니면 0 이 아니라 null — 차트가 0 으로 꺼지거나 확률 0% 로 보이지 않게")
    void missingNumbersAreNull() {
        assertThat(GeminiService.toNumber(null)).isNull();
        assertThat(GeminiService.toNumber("abc")).isNull();
        assertThat(GeminiService.toNumber("2750")).isEqualTo(2750.0);
    }
}
