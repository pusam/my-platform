package com.myplatform.backend.service;

import com.myplatform.backend.dto.AiStockRecommendationDto;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockCatalystRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 장이 닫힌 날엔 요청 경로로도 시그널 행·TOP PICK 텔레그램이 생기지 않는다(2026-10-19).
 *
 * <p>재현(리뷰 에이전트 지적): 휴장일 게이트는 크론 입구에만 있었다(10/7). 휴장일엔 워머가 멈춰 AI 분석 캐시(Redis)가 비어 있고,
 * 사용자가 종목 상세를 열면 복합 신호 → {@code AiStockAnalysisService.getAnalysis} → 재계산이 돌아 70점 이상이면 휴장일 날짜의
 * AI 시그널 행이, 90점 이상이면 직전 거래일 데이터로 'AI TOP PICK' 텔레그램이 나갔다. 복합 신호(4개 이상 일치)도 휴장일 날짜
 * 행을 남겼다 — 9/28 에 지운 휴장일 가짜 행과 같은 부류. 그래서 기록은 {@code SignalOutcomeService.record} 한 곳에서 막는다.
 */
class HolidayRequestPathGuardTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> none() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> of(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static SignalOutcomeService outcomes(SignalOutcomeRepository repo, boolean marketClosed) {
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(marketClosed);
        return new SignalOutcomeService(repo, mock(StockPriceService.class), mock(StockCatalystRepository.class),
                none(), none(), none(), none(), none(), none(), none(), none(), none(),
                of(calendar), none(), none());
    }

    @Test
    @DisplayName("재현: 휴장일엔 어느 경로에서 오든 시그널을 기록하지 않는다")
    void noSignalRowOnClosedDay() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);

        outcomes(repo, true).record("AI_BUY", "005930", "삼성전자", 80, new BigDecimal("70000"));

        verify(repo, never()).findExisting(anyString(), anyString(), any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("거래일엔 종전대로 기록 단계로 간다")
    void tradingDayStillRecords() {
        SignalOutcomeRepository repo = mock(SignalOutcomeRepository.class);
        when(repo.findExisting(anyString(), anyString(), any())).thenReturn(List.of());

        outcomes(repo, false).record("AI_BUY", "005930", "삼성전자", 80, new BigDecimal("70000"));

        verify(repo).findExisting(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("재현: 휴장일엔 'AI TOP PICK' 텔레그램을 보내지 않는다 — 직전 거래일 데이터로 나갔다")
    void noTopPickAlertOnClosedDay() {
        AiStockAnalysisService service = mock(AiStockAnalysisService.class, CALLS_REAL_METHODS);
        MarketCalendarService calendar = mock(MarketCalendarService.class);
        when(calendar.isMarketClosed()).thenReturn(true);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);
        ReflectionTestUtils.setField(service, "telegramService", telegram);
        ReflectionTestUtils.setField(service, "alertedToday", new ConcurrentHashMap<String, LocalDate>());
        AiStockRecommendationDto pick = AiStockRecommendationDto.builder().stockCode("005930").stockName("삼성전자")
                .type("short").totalScore(95).buyReasons(List.of("수급")).build();

        ReflectionTestUtils.invokeMethod(service, "sendHighScoreAlerts", List.of(pick), List.of());

        verify(telegram, never()).sendSignal(anyString());
        verify(telegram, never()).sendBriefing(anyString());
        verify(telegram, never()).sendRisk(anyString());
        verify(telegram, never()).sendMessage(anyString());
        Map<?, ?> alerted = (Map<?, ?>) ReflectionTestUtils.getField(service, "alertedToday");
        org.assertj.core.api.Assertions.assertThat(alerted).isEmpty();
    }
}
