package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 부채비율은 일별 재무 행에 저장된다 — {@code collectStockFinancialDataSimple}(2026-10-07).
 *
 * <p>재현(10/7 운영): 9/28 이후 재무 수집 경로는 이것 하나인데, {@code getFinancialRatios} 가 만들어 둔 부채비율(부채총계÷자본총계)을
 * 저장하지 않아 그날 KIS 일별 행 2,666개 전부 {@code debt_ratio} 가 NULL 이었다(9/23 까지는 23:00 재수집이 51종목만 채웠다).
 * 그래서 저평가 트랙·종합추천 가치 점수의 부채 4점이 전 종목 0 이 됐고(저평가 트랙 334종목이 16점 동점, 부채 값이 남은
 * GS·DL이앤씨만 19점), 마법의 공식 '부채비율 200% 이하' 필터는 null 통과라 아무것도 거르지 않았다. GS 실측: 부채총계 168,161억
 * ÷ 자본총계 210,252억 = 79.98%(9/22 까지 저장돼 있던 값과 같다).
 */
class FinancialDebtRatioPersistTest {

    private StockFinancialDataCollector collector;
    private StockFinancialData today;

    @BeforeEach
    void setUp() throws Exception {
        StockFinancialDataRepository dailyRepo = mock(StockFinancialDataRepository.class);
        DartControllingFinancialRepository dartRepo = mock(DartControllingFinancialRepository.class);
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        collector = spy(new StockFinancialDataCollector(dailyRepo, mock(StockQuarterlyFinancialRepository.class), kis,
                mock(RestTemplate.class), new ObjectMapper(), mock(StockMasterService.class), dartRepo,
                mock(DartCompanyRepository.class)));
        today = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(today));
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());
        when(kis.getAccessToken()).thenReturn("token");
        when(kis.getStockPrice("078930")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"GS\",\"stck_prpr\":\"105000\",\"hts_avls\":\"97819\","
                        + "\"per\":\"4.20\",\"pbr\":\"0.60\",\"eps\":\"25000\",\"bps\":\"175000\",\"lstn_stcn\":\"92915378\"}}"));
    }

    /** {@code getFinancialRatios} 결과 — 재무상태표 부채총계·자본총계와 그 비율. */
    private void ratios(BigDecimal debtRatio) {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("totalDebt", new BigDecimal("168161"));
        r.put("totalEquity", new BigDecimal("210252"));
        r.put("totalAssets", new BigDecimal("378413"));
        if (debtRatio != null) r.put("debtRatio", debtRatio);
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());
    }

    @Test
    @DisplayName("재현: 재무상태표로 만든 부채비율(GS 79.98%)을 일별 행에 저장한다")
    void debtRatioIsPersisted() {
        ratios(new BigDecimal("79.98"));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(today.getDebtRatio()).isEqualByComparingTo("79.98");
        assertThat(today.getTotalDebt()).isEqualByComparingTo("168161");
    }

    @Test
    @DisplayName("부채비율을 못 받은 회차(없음·파싱 실패 0)는 쓰지 않는다 — 결측을 0 으로 만들지도, 같은 날 받아 둔 값을 지우지도 않는다")
    void missingDebtRatioNeitherZeroNorErase() {
        ratios(BigDecimal.ZERO);
        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();
        assertThat(today.getDebtRatio()).as("파싱 실패 0 은 결측").isNull();

        today.setDebtRatio(new BigDecimal("79.98"));   // 08:30 회차가 저장해 둔 값
        ratios(null);
        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();
        assertThat(today.getDebtRatio()).as("15:38 회차가 못 받았다고 지우지 않는다").isEqualByComparingTo("79.98");
    }
}
