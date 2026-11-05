package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDetailDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종목 상세 수급 출처·AI 분석 세션 이름은 달력을 본다 — 장이 닫힌 날은 '휴장'(2026-10-19).
 *
 * <p>재현(리뷰 에이전트 지적): 출처를 시각만 보고 정해 08:00~20:00 이면 요일·공휴일과 무관하게 '실시간'이었다. 그래서 토요일·평일
 * 공휴일(10/9 한글날) 10시에 배지는 '실시간', 외국인·기관은 '-', 안내문은 "장 마감(15:40) 뒤 제공 — 장중엔…"이었고(바로 위 체결강도
 * 게이지는 '휴장'), 거래일 16:30 에 조회가 실패해도 같은 "15:40 뒤 제공"이 나왔다. 종목 상세 AI 분석 프롬프트도 휴장일 10시를
 * "분석 기준일 …(장중)"으로 넣어 마지막 거래일 데이터를 장중 데이터처럼 분석하게 했다.
 */
class StockDetailClosedDayTest {

    @Test
    @DisplayName("재현: 장이 닫힌 날의 수급 출처는 시각과 무관하게 '휴장'")
    void closedDaySourceIsHoliday() {
        assertThat(StockDetailService.supplySource(true, false, false)).isEqualTo("휴장");
        assertThat(StockDetailService.supplySource(true, true, false)).isEqualTo("휴장");
        assertThat(StockDetailService.supplySource(true, false, true)).isEqualTo("휴장");
    }

    @Test
    @DisplayName("거래일은 종전 그대로 — 장전(초기화)·실시간·일별(DB)")
    void tradingDayUnchanged() {
        assertThat(StockDetailService.supplySource(false, true, false)).isEqualTo("장전(초기화)");
        assertThat(StockDetailService.supplySource(false, false, false)).isEqualTo("실시간");
        assertThat(StockDetailService.supplySource(false, false, true)).isEqualTo("일별(DB)");
    }

    @Test
    @DisplayName("외국인·기관 당일 집계는 거래일 15:40 부터 나와 있어야 한다 — 그 뒤 비면 '마감 뒤 제공'이라 하지 않는다")
    void investorDataExpectedAfterClose() {
        assertThat(StockDetailService.investorDataExpected(false, LocalTime.of(15, 39))).isFalse();
        assertThat(StockDetailService.investorDataExpected(false, LocalTime.of(15, 40))).isTrue();
        assertThat(StockDetailService.investorDataExpected(false, LocalTime.of(19, 0))).isTrue();
        assertThat(StockDetailService.investorDataExpected(true, LocalTime.of(16, 30))).as("휴장일엔 나올 것이 없다").isFalse();
    }

    @Test
    @DisplayName("재현: 휴장일의 AI 분석 프롬프트는 '장중'이 아니라 '휴장 — 마지막 거래일 값'")
    void geminiPromptSaysClosedDay() {
        StockDetailService service = mock(StockDetailService.class, CALLS_REAL_METHODS);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(true);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);
        ReflectionTestUtils.setField(service, "clock", java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));   // 목은 필드 초기화를 건너뛴다

        StockDetailDto.PriceInfo price = new StockDetailDto.PriceInfo();
        price.setCurrentPrice(new java.math.BigDecimal("70000"));
        price.setChangeRate(java.math.BigDecimal.ONE);
        String prompt = service.buildGeminiPrompt(StockDetailDto.builder().stockCode("005930").stockName("삼성전자").price(price).build());

        assertThat(prompt).contains("휴장").doesNotContain("(장중)");
    }
}
