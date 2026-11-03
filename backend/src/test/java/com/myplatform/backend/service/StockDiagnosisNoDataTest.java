package com.myplatform.backend.service;

import com.myplatform.backend.dto.StockDiagnosisDto;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 재무 데이터가 없는 종목의 진단(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: '분석 불가' 진단의 종합점수를 0 으로 내려 화면이 '진단 점수 0'(빨강·위험), '종합 점수: 0점', 펀더멘털 취약 헤드라인을
 * 만들었다 — 0점은 '아주 나쁘다'는 뜻이지 '모른다'가 아니다(§4c). 점수는 모름(null)이다.
 */
class StockDiagnosisNoDataTest {

    @Test
    @DisplayName("재현: 재무 데이터가 없으면 종합점수는 null(모름) — 0점이 아니다")
    void noFinancialDataHasNoScore() {
        StockFinancialDataRepository financial = mock(StockFinancialDataRepository.class);
        when(financial.findTop10ByStockCodeOrderByReportDateDesc(anyString())).thenReturn(List.of());
        StockAnalysisService svc = new StockAnalysisService(financial, mock(InvestorDailyTradeRepository.class),
                mock(StockPriceHistoryRepository.class), mock(StockPriceRepository.class),
                mock(TechnicalIndicatorService.class), mock(KoreaInvestmentService.class), new MarketCalendarService());

        StockDiagnosisDto d = svc.diagnose("900999");

        assertThat(d.getVerdict()).isEqualTo("분석 불가");
        assertThat(d.getOverallScore()).as("0 은 '아주 나쁘다'지 '모른다'가 아니다").isNull();
    }
}
