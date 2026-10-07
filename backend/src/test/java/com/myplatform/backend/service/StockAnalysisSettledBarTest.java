package com.myplatform.backend.service;

import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마감이 확정되지 않은 날의 일봉은 저장하지 않는다(2026-10-07, 사용자 결정 '추천 방향' = 확정 봉만).
 *
 * <p>재현(10/7 운영): 09:00 종합추천 계산이 직전 거래일 봉이 없는 종목(그날 622종목)의 일봉을 받으며 <b>오늘 형성 봉</b>
 * (09:00~10:10 값)까지 저장했고, 그 값이 11:30·14:00·17:00 기술 채점에서 '오늘 종가'로 쓰였다. 그날 매수 후보 두 종목은 그 봉
 * 때문에 후보가 됐다 — 삼성SDI 는 09:05 값 563,000(어제 종가 575,000)으로 RSI 62.9→59.1·볼린저 상단 돌파 감점 사라짐·골든크로스
 * 생김 → 기술 4→19점, 삼성전자는 정배열이 생겨 9→15점(어제 종가까지만 쓰면 총점 46·47점으로 55 컷 밖). 18:30 보정은 하루 400종목만
 * 고쳐 나머지 형성 봉은 다음 날 점수에도 '어제 종가'로 남았다. 확정 기준은 D+3 평가와 같은 {@code lastSettledTradingDay}
 * (KRX 15:40 + 30분).
 */
class StockAnalysisSettledBarTest {

    private static KoreaInvestmentService.OhlcvData bar(LocalDate d, String close) {
        BigDecimal c = new BigDecimal(close);
        return new KoreaInvestmentService.OhlcvData(c, c, c, c, new BigDecimal("1000"), d);
    }

    @Test
    @DisplayName("재현: 마감 전 날짜의 봉은 저장하지 않고, 확정된 날의 봉만 저장한다")
    void unsettledBarIsNotSaved() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);   // 어떤 시각에도 아직 마감 전
        LocalDate settled = LocalDate.of(2026, 9, 1);
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        when(kis.getDailyOhlcv("006400", 60)).thenReturn(List.of(bar(tomorrow, "563000"), bar(settled, "575000")));
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        when(history.findByStockCodeAndTradeDate(anyString(), any())).thenReturn(Optional.empty());
        StockPriceRepository prices = mock(StockPriceRepository.class);
        when(prices.findTopByStockCodeOrderByFetchedAtDesc(anyString())).thenReturn(Optional.empty());

        new StockAnalysisService(mock(StockFinancialDataRepository.class), mock(InvestorDailyTradeRepository.class),
                history, prices, mock(TechnicalIndicatorService.class), kis, new MarketCalendarService())
                .collectPriceHistory("006400");

        ArgumentCaptor<StockPriceHistory> saved = ArgumentCaptor.forClass(StockPriceHistory.class);
        verify(history, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(StockPriceHistory::getTradeDate).containsExactly(settled);
    }

    @Test
    @DisplayName("확정 판정은 D+3 평가와 같은 경계 — 거래일 장중엔 오늘이 아니라 직전 거래일까지, 마감+30분 뒤엔 오늘까지")
    void settledBoundaryMatchesD3() {
        MarketCalendarService cal = new MarketCalendarService();
        LocalDate wed = LocalDate.of(2026, 10, 7), tue = LocalDate.of(2026, 10, 6);
        LocalDate during = SignalD3EvaluationService.lastSettledTradingDay(LocalDateTime.of(2026, 10, 7, 11, 30), cal);
        LocalDate after = SignalD3EvaluationService.lastSettledTradingDay(LocalDateTime.of(2026, 10, 7, 16, 20), cal);
        assertThat(during).isEqualTo(tue);
        assertThat(after).isEqualTo(wed);
        assertThat(StockAnalysisService.isSettledBar(wed, during)).isFalse();
        assertThat(StockAnalysisService.isSettledBar(tue, during)).isTrue();
        assertThat(StockAnalysisService.isSettledBar(wed, after)).isTrue();
        assertThat(StockAnalysisService.isSettledBar(null, during)).isFalse();
    }
}
