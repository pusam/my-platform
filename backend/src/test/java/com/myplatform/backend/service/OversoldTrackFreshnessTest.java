package com.myplatform.backend.service;

import com.myplatform.backend.dto.TechnicalIndicatorsDto;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 낙폭과대 트랙 — 오래된 봉으로 채점하지 않는다(2026-10-04).
 *
 * <p>재현: 종목별 최신 봉의 날짜를 보지 않아, 봉 수집이 몇 주 전에 끊긴 종목이 그때의 RSI·이격도·'반등'으로 낙폭과대 후보에
 * 올랐다. 종합추천 기술 채점의 노후 가드(isPriceHistoryFresh — 직전 거래일보다 오래되면 제외)를 같은 규칙으로 쓴다.
 */
class OversoldTrackFreshnessTest {

    private static List<StockPriceHistory> bars(String code, LocalDate latest) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {   // 최신순
            rows.add(StockPriceHistory.builder()
                    .stockCode(code).stockName(code)
                    .tradeDate(latest.minusDays(i))
                    .closePrice(new BigDecimal(i == 0 ? "8500" : "10000"))
                    .changeRate(new BigDecimal(i == 0 ? "1.5" : "0"))
                    .volume(new BigDecimal(i == 0 ? "300000" : "100000"))
                    .build());
        }
        return rows;
    }

    @Test
    @DisplayName("재현: 최신 봉이 직전 거래일보다 오래된 종목은 낙폭과대 후보에서 빠진다")
    void staleBarsAreExcluded() {
        RecommendationService service = mock(RecommendationService.class, CALLS_REAL_METHODS);
        StockPriceHistoryRepository repo = mock(StockPriceHistoryRepository.class);
        TechnicalIndicatorService ta = mock(TechnicalIndicatorService.class);
        StockStatusService status = mock(StockStatusService.class);
        RiskManagementService risk = mock(RiskManagementService.class);
        MarketCalendarService calendar = mock(MarketCalendarService.class);

        LocalDate prevTradingDay = LocalDate.now().minusDays(1);
        when(calendar.minusTradingDays(any(), anyInt())).thenReturn(prevTradingDay);
        when(repo.findStockCodesWithMinHistory(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of("FRESH0", "STALE0"));
        List<StockPriceHistory> all = new ArrayList<>(bars("FRESH0", LocalDate.now()));
        all.addAll(bars("STALE0", LocalDate.now().minusDays(30)));
        when(repo.findByStockCodesSince(anyList(), any())).thenReturn(all);
        when(status.isActive(anyString())).thenReturn(true);
        when(ta.calculate(anyList())).thenReturn(TechnicalIndicatorsDto.builder()
                .rsi14(new BigDecimal("22")).ma20(new BigDecimal("10000")).build());
        when(risk.quickDangerCheck(anyString(), anyString())).thenReturn(false);

        ReflectionTestUtils.setField(service, "priceHistoryRepository", repo);
        ReflectionTestUtils.setField(service, "technicalIndicatorService", ta);
        ReflectionTestUtils.setField(service, "stockStatusService", status);
        ReflectionTestUtils.setField(service, "riskManagementService", risk);
        ReflectionTestUtils.setField(service, "marketCalendar", calendar);

        List<RecommendationService.RecommendationDto> top = ReflectionTestUtils.invokeMethod(service, "calculateOversoldTop10");

        assertThat(top).extracting(RecommendationService.RecommendationDto::getStockCode).containsExactly("FRESH0");
    }
}
