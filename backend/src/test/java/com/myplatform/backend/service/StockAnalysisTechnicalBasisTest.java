package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import com.myplatform.backend.util.ChartNarrativeBuilder;
import com.myplatform.backend.util.PullbackEntryCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 종목 상세 '20일선'·'RSI' 칸의 기준 — 지표가 쓴 마지막 확정 종가의 날짜를 함께 준다(2026-10-07, 결정 대기 ⓑ → '추천 방향').
 *
 * <p>재현(10/7 운영): 같은 종목의 20일선 이격이 요약 칸 +4.5%·차트 해설 +5.3%·AI 근거 '5% 이상'으로 갈렸다 — 요약 칸은 저장된 확정
 * 봉(직전 거래일 종가), 차트 해설·AI 는 오늘 시세 기준이라 셋 다 맞는 값인데 기준을 말하지 않았다. 게다가 요약 칸의 계산은 저장 봉이
 * 모자란 종목이면 KIS 응답을 바로 써서 <b>오늘 형성 봉</b>(장중 시세)까지 넣었다 — 저장은 이미 확정 봉만 하는데 계산은 아니었다.
 */
class StockAnalysisTechnicalBasisTest {

    @Test
    @DisplayName("재현: 저장 봉이 모자라 KIS 응답을 쓸 때도 마감 전 봉은 계산에서 빼고, 그 기준 날짜(마지막 확정 종가일)를 준다")
    void kisPathUsesSettledBarsAndReportsBasisDate() {
        MarketCalendarService calendar = new MarketCalendarService();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate lastSettled = SignalD3EvaluationService.lastSettledTradingDay(
                java.time.LocalDateTime.now(ZoneId.of("Asia/Seoul")), calendar);

        // 최신순: 마감 전 봉(내일 날짜 — 어떤 시각에도 아직 마감 전, 종가 999,999) + 확정 봉 30개(종가 전부 100)
        List<KoreaInvestmentService.OhlcvData> bars = new ArrayList<>();
        bars.add(new KoreaInvestmentService.OhlcvData(bd(999999), bd(999999), bd(999999), bd(999999), bd(1000), today.plusDays(1)));
        LocalDate d = lastSettled;
        for (int i = 0; i < 30; i++) {
            bars.add(new KoreaInvestmentService.OhlcvData(bd(100), bd(100), bd(100), bd(100), bd(1000), d));
            d = calendar.minusTradingDays(d, 1);
        }
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        when(kis.getDailyOhlcv(eq("006400"), any(Integer.class))).thenReturn(bars);
        StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        when(history.findByStockCodeOrderByTradeDateDesc(anyString(), any())).thenReturn(List.of());   // 저장 봉 없음 → KIS 경로
        when(history.findByStockCodeAndTradeDate(anyString(), any())).thenReturn(Optional.empty());
        StockPriceRepository prices = mock(StockPriceRepository.class);
        when(prices.findTopByStockCodeOrderByFetchedAtDesc(anyString())).thenReturn(Optional.empty());

        StockAnalysisService svc = new StockAnalysisService(mock(StockFinancialDataRepository.class),
                mock(InvestorDailyTradeRepository.class), history, prices, new TechnicalIndicatorService(kis), kis, calendar);

        StockDiagnosisDto.TechnicalAnalysisDto t = ReflectionTestUtils.invokeMethod(svc, "analyzeTechnical", "006400");

        assertThat(t).isNotNull();
        assertThat(t.getBasisDate()).isEqualTo(lastSettled);
        assertThat(t.getDisparity20()).as("확정 종가 100 만으로 — 999,999 형성 봉이 들어가면 이격이 수천 %").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("차트 해설의 이격 문장은 오늘 시세 기준이라고 말한다")
    void narrativeSaysTodayBasis() {
        ChartNarrativeBuilder.Narrative n = ChartNarrativeBuilder.build(
                new PullbackEntryCalculator.Metrics(0.5, 3, 2, null), null, 5.3);
        String text = String.join(" ", n.sections().stream().flatMap(s -> s.lines().stream()).toList());
        assertThat(text).contains("20일선 대비 +5.3% 이격입니다(오늘 시세 기준).");
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }
}
