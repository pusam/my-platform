package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartControllingFinancial;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * PER·PBR·ROE 는 DART 지배주주 기준이 있으면 그것으로 — {@code StockFinancialDataCollector}(2026-09-30).
 *
 * <p>다우기술(023590) 실측: KIS 연결 순이익(비지배 포함) 17,556억으로 만든 PER 0.9·PBR 0.19 는 키움증권 소수주주 몫까지 센
 * 값이다. DART 지배주주 순이익 최근 4분기 7,488.2억·지배지분 자본 38,832.8억으로 만들면 PER 2.1·PBR 0.41(네이버 2.11·0.40).
 */
class FinancialControllingBasisTest {

    private StockFinancialDataRepository dailyRepo;
    private DartControllingFinancialRepository dartRepo;
    private KoreaInvestmentService kis;
    private StockFinancialDataCollector collector;
    private StockFinancialData today;

    @BeforeEach
    void setUp() throws Exception {
        dailyRepo = mock(StockFinancialDataRepository.class);
        dartRepo = mock(DartControllingFinancialRepository.class);
        kis = mock(KoreaInvestmentService.class);
        StockFinancialDataCollector real = new StockFinancialDataCollector(dailyRepo,
                mock(StockQuarterlyFinancialRepository.class), kis, mock(RestTemplate.class), new ObjectMapper(),
                mock(StockMasterService.class), dartRepo);
        collector = spy(real);

        today = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(today));
        when(kis.getAccessToken()).thenReturn("token");
        // KIS 현재가 API — per·eps·pbr·bps 는 지배주주 기준 최근 결산(연간) 값이다
        when(kis.getStockPrice("023590")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"다우기술\",\"stck_prpr\":\"36500\",\"hts_avls\":\"15800\","
                        + "\"per\":\"3.24\",\"pbr\":\"0.46\",\"eps\":\"11260\",\"bps\":\"79869\",\"lstn_stcn\":\"43287312\"}}"));
    }

    /** KIS 손익계산서·재무상태표 결과 — 연결 순이익 17,556억(비지배 포함), 자본총계 84,000억(비지배 포함). */
    private void kisTtm(boolean withTtm) {
        Map<String, BigDecimal> ratios = new HashMap<>();
        ratios.put("roe", new BigDecimal("15.95"));
        ratios.put("totalEquity", new BigDecimal("84000"));
        if (withTtm) {
            ratios.put("netIncome", new BigDecimal("17556"));
            ratios.put("revenue", new BigDecimal("348197"));
            ratios.put("operatingProfit", new BigDecimal("22444"));
            ratios.put("operatingMargin", new BigDecimal("6.45"));
        }
        doReturn(ratios).when(collector).getFinancialRatios(anyString(), anyString());
    }

    private static DartControllingFinancial report(int year, String reprt, String ni, String niPrev, String equity,
                                                   LocalDate filedOn) {
        return DartControllingFinancial.builder().stockCode("023590").corpCode("00176914").bsnsYear(year).reprtCode(reprt)
                .fsDiv("CFS").status(DartControllingFinancial.STATUS_OK).filedOn(filedOn)
                .ctrlNetIncome(ni == null ? null : new BigDecimal(ni))
                .ctrlNetIncomePrev(niPrev == null ? null : new BigDecimal(niPrev))
                .ctrlEquity(equity == null ? null : new BigDecimal(equity))
                .collectedAt(LocalDateTime.now()).build();
    }

    /** 다우기술 2026 반기 + 2025 사업보고서 — 접수일은 오늘 기준 상대값(테스트가 날짜와 함께 썩지 않게). */
    private void dartReports(boolean withPriorAnnual) {
        LocalDate now = LocalDate.now();
        DartControllingFinancial h1 = report(2026, "11012", "4923.83", "2487.45", "38832.82", now.minusDays(47));
        DartControllingFinancial fy = report(2025, "11011", "5051.82", "3558.35", "34464.35", now.minusDays(196));
        when(dartRepo.findByStockCodeAndStatus(eq("023590"), eq(DartControllingFinancial.STATUS_OK)))
                .thenReturn(withPriorAnnual ? List.of(h1, fy) : List.of(h1));
    }

    @Test
    @DisplayName("DART 지배주주 값이 있으면 PER 2.1 · PBR 0.41 · ROE 19.28 — 기준 CTRL")
    void controllingBasis() {
        kisTtm(true);
        dartReports(true);

        assertThat(collector.collectStockFinancialDataSimple("023590")).isTrue();

        assertThat(today.getEps()).as("7,488.20억 ÷ 43,287,312주").isEqualByComparingTo("17299");
        assertThat(today.getPer()).isEqualByComparingTo("2.1");
        assertThat(today.getBps()).as("38,832.82억 ÷ 43,287,312주").isEqualByComparingTo("89709");
        assertThat(today.getPbr()).isEqualByComparingTo("0.41");
        assertThat(today.getRoe()).as("지배주주 순이익 ÷ 지배지분 자본").isEqualByComparingTo("19.28");
        assertThat(today.getPerBasis()).isEqualTo("CTRL");
        assertThat(today.getNetIncome()).as("순이익 칸은 KIS 연결 순이익 그대로(다른 소비처가 쓴다)").isEqualByComparingTo("17556");
        assertThat(today.getTotalEquity()).isEqualByComparingTo("84000");
    }

    @Test
    @DisplayName("DART 값이 없으면 종전 그대로 — 연결 순이익 PER 0.9 · 자본총계 PBR 0.19, 기준 CONSOL")
    void withoutDartKeepsConsolidated() {
        kisTtm(true);
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());

        assertThat(collector.collectStockFinancialDataSimple("023590")).isTrue();

        assertThat(today.getPer()).isEqualByComparingTo("0.9");
        assertThat(today.getPbr()).isEqualByComparingTo("0.19");
        assertThat(today.getRoe()).isEqualByComparingTo("20.90");
        assertThat(today.getPerBasis()).isEqualTo("CONSOL");
    }

    @Test
    @DisplayName("KIS TTM 도 없으면 현재가 API 값(지배주주 연간) — 기준 KIS")
    void withoutTtmUsesKisAnnual() {
        kisTtm(false);
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());

        assertThat(collector.collectStockFinancialDataSimple("023590")).isTrue();

        assertThat(today.getPer()).isEqualByComparingTo("3.24");
        assertThat(today.getPerBasis()).isEqualTo("KIS");
    }

    @Test
    @DisplayName("직전 사업보고서가 없으면 순이익은 모른다 — PER 은 종전, PBR 만 지배지분 자본으로")
    void equityOnly() {
        kisTtm(true);
        dartReports(false);

        assertThat(collector.collectStockFinancialDataSimple("023590")).isTrue();

        assertThat(today.getPer()).isEqualByComparingTo("0.9");
        assertThat(today.getPerBasis()).isEqualTo("CONSOL");
        assertThat(today.getPbr()).isEqualByComparingTo("0.41");
        assertThat(today.getRoe()).as("순이익 정의가 연결이면 ROE 도 연결 기준 그대로(분자·분모를 섞지 않는다)")
                .isEqualByComparingTo("20.90");
    }

    @Test
    @DisplayName("DART 표 조회가 터져도 수집은 계속 — 종전 정의로")
    void dartLookupFailureIsNotFatal() {
        kisTtm(true);
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenThrow(new RuntimeException("db down"));

        assertThat(collector.collectStockFinancialDataSimple("023590")).isTrue();

        assertThat(today.getPer()).isEqualByComparingTo("0.9");
        assertThat(today.getPerBasis()).isEqualTo("CONSOL");
    }
}
