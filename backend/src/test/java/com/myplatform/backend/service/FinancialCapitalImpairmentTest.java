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
 * 자본잠식(자본총계 음수) 종목의 PBR·ROE(2026-10-19, 리뷰 에이전트 지적).
 *
 * <p>재현: ① 최신 분기 자본이 음수면 '없음'으로 보고 작년 연간 자본(양수)을 썼다(수집기 재무상태표 단계 —
 * {@code FinancialRatioFieldMappingTest}). ② 그 자본을 못 쓰는 경로에선 PBR 이 KIS 현재가 API 값(작년 결산 BPS 기준)으로 남고
 * ROE 는 TTM EPS ÷ KIS BPS 로 만들어졌다. ③ KIS BPS 까지 음수면 ROE 부호를 순이익에 맞춰 뒤집어 EPS 2,000 ÷ BPS −50 =
 * +4,000% 가 됐다 — 마법의 공식(ROE &gt; 0, ROE 순위)에서 1위가 될 수 있는 값이다. 음수 자본으로 만든 PBR·ROE 는 의미가 없다 —
 * 모른다(null).
 */
class FinancialCapitalImpairmentTest {

    private StockFinancialDataCollector collector;
    private StockFinancialData row;

    @BeforeEach
    void setUp() {
        StockFinancialDataRepository dailyRepo = mock(StockFinancialDataRepository.class);
        DartControllingFinancialRepository dartRepo = mock(DartControllingFinancialRepository.class);
        collector = spy(new StockFinancialDataCollector(dailyRepo, mock(StockQuarterlyFinancialRepository.class), kis,
                mock(RestTemplate.class), new ObjectMapper(), mock(StockMasterService.class), dartRepo,
                mock(DartCompanyRepository.class)));
        row = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(row));
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());
        when(kis.getAccessToken()).thenReturn("token");
    }

    private final KoreaInvestmentService kis = mock(KoreaInvestmentService.class);

    /** 현재가 API — 주가 1,000원 · 상장주식 1,000만 주 · KIS BPS(작년 결산). */
    private void price(String bps, String pbr) throws Exception {
        when(kis.getStockPrice("900100")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"잠식\",\"stck_prpr\":\"1000\",\"hts_avls\":\"100\","
                        + "\"per\":\"5.0\",\"pbr\":\"" + pbr + "\",\"eps\":\"200\",\"bps\":\"" + bps + "\",\"lstn_stcn\":\"10000000\"}}"));
    }

    /** TTM 순이익 200억(흑자) — 자본총계는 넘기면 그 값. */
    private void ratios(String totalEquity) {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("netIncome", new BigDecimal("200"));
        r.put("revenue", new BigDecimal("1000"));
        r.put("operatingProfit", new BigDecimal("50"));
        if (totalEquity != null) {
            r.put("totalEquity", new BigDecimal(totalEquity));
            r.put("totalDebt", new BigDecimal("550"));
        }
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());
    }

    @Test
    @DisplayName("재현: 최신 자본총계가 음수면 PBR·ROE 는 모른다 — 작년 결산 BPS 로 만든 PBR 0.80·ROE 66.67% 를 남기지 않는다")
    void negativeEquityHasNoPbrOrRoe() throws Exception {
        price("3000", "0.80");
        ratios("-50");

        assertThat(collector.collectStockFinancialDataSimple("900100")).isTrue();

        assertThat(row.getTotalEquity()).isEqualByComparingTo("-50");
        assertThat(row.getPbr()).isNull();
        assertThat(row.getRoe()).isNull();
        assertThat(row.getBps()).as("BPS 는 음수 그대로 — 자본잠식이라는 사실").isEqualByComparingTo("-500");
        assertThat(row.getPer()).as("흑자라 PER 은 그대로 — EPS 2,000원").isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("재현: 재무상태표가 없고 KIS BPS 가 음수면 ROE 부호를 뒤집어 +4,000% 를 만들지 않는다")
    void negativeKisBpsDoesNotFlipRoe() throws Exception {
        price("-50", "-20.00");
        ratios(null);

        assertThat(collector.collectStockFinancialDataSimple("900100")).isTrue();

        assertThat(row.getRoe()).as("고치기 전: 2,000 ÷ −50 = −4,000% → 부호 보정 +4,000%").isNull();
        assertThat(row.getPbr()).isNull();
    }

    @Test
    @DisplayName("자본이 양수면 종전대로 — ROE = 순이익 ÷ 자본총계, PBR = 주가 ÷ (자본총계 ÷ 주식 수)")
    void positiveEquityUnchanged() throws Exception {
        price("3000", "0.80");
        ratios("1000");

        assertThat(collector.collectStockFinancialDataSimple("900100")).isTrue();

        assertThat(row.getRoe()).isEqualByComparingTo("20.00");
        assertThat(row.getBps()).isEqualByComparingTo("10000");
        assertThat(row.getPbr()).isEqualByComparingTo("0.10");
    }
}
