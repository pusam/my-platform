package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompany;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
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
 * 우선주가 상장된 회사의 EPS·BPS 는 보통주 + 우선주 주식 수로 나눈다(2026-11-05).
 *
 * <p>재현(현대차 11/5 운영값): 수집기가 KIS {@code lstn_stcn}(그 코드의 상장주식수 = 보통주 203,466천 주)만으로 나눠 최근 4분기 지배주주 순이익
 * 81,465.87억의 EPS 가 40,039원, PER 이 7.5 였다. 네이버는 EPS 30,697원·PER 9.64·BPS 467,197원 — 순이익·자본은 우선주 몫까지 포함한
 * 회사 전체 값이라 분모도 회사 전체여야 한다(우선주 3종 59,417천 주). 우선주가 있는 96개 회사 대조: 네이버 ±5% 안 42 → 79, 20% 초과 15 → 1.
 */
class FinancialPreferredShareTest {

    private static final String HYUNDAI = "005380";
    private static final long PREFERRED = 59_417_000L;

    private StockFinancialDataRepository dailyRepo;
    private DartControllingFinancialRepository dartRepo;
    private DartCompanyRepository companyRepo;
    private KoreaInvestmentService kis;
    private StockStatusService status;
    private StockFinancialDataCollector collector;
    private StockFinancialData today;

    @BeforeEach
    void setUp() throws Exception {
        dailyRepo = mock(StockFinancialDataRepository.class);
        dartRepo = mock(DartControllingFinancialRepository.class);
        companyRepo = mock(DartCompanyRepository.class);
        kis = mock(KoreaInvestmentService.class);
        status = mock(StockStatusService.class);
        StockFinancialDataCollector real = new StockFinancialDataCollector(dailyRepo,
                mock(StockQuarterlyFinancialRepository.class), kis, mock(RestTemplate.class), new ObjectMapper(),
                mock(StockMasterService.class), dartRepo, companyRepo);
        collector = spy(real);
        collector.stockStatusService = status;

        today = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(today));
        when(kis.getAccessToken()).thenReturn("token");
        when(kis.getStockPrice(HYUNDAI)).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"현대차\",\"stck_prpr\":\"298500\",\"hts_avls\":\"607347\","
                        + "\"per\":\"9.5\",\"pbr\":\"0.64\",\"eps\":\"31300\",\"bps\":\"470000\",\"lstn_stcn\":\"203466000\"}}"));
        when(status.isPreferredSharesLoaded()).thenReturn(true);
        when(status.preferredListedShares(HYUNDAI)).thenReturn(PREFERRED);
    }

    /** KIS 연결 값 — 순이익 90,000억, 자본총계 1,300,000억(비지배 포함). */
    private void kisTtm() {
        Map<String, BigDecimal> ratios = new HashMap<>();
        ratios.put("netIncome", new BigDecimal("90000"));
        ratios.put("revenue", new BigDecimal("1860000"));
        ratios.put("operatingProfit", new BigDecimal("110000"));
        ratios.put("totalEquity", new BigDecimal("1300000"));
        doReturn(ratios).when(collector).getFinancialRatios(anyString(), anyString());
    }

    private static DartControllingFinancial report(int year, String reprt, String ni, String niPrev, String equity, LocalDate filedOn) {
        return DartControllingFinancial.builder().stockCode(HYUNDAI).corpCode("00164742").bsnsYear(year).reprtCode(reprt)
                .fsDiv("CFS").status(DartControllingFinancial.STATUS_OK).filedOn(filedOn)
                .ctrlNetIncome(ni == null ? null : new BigDecimal(ni))
                .ctrlNetIncomePrev(niPrev == null ? null : new BigDecimal(niPrev))
                .ctrlEquity(equity == null ? null : new BigDecimal(equity))
                .collectedAt(LocalDateTime.now()).build();
    }

    /** 현대차 2026 반기 + 2025 사업보고서 — 지배주주 순이익 48,561.97 + 94,459.87 − 61,555.97 = 81,465.87억. */
    private void dartReports() {
        when(companyRepo.findById(HYUNDAI)).thenReturn(Optional.of(DartCompany.builder().stockCode(HYUNDAI)
                .corpCode("00164742").fiscalMonth("12").collectedAt(LocalDateTime.now()).build()));
        LocalDate now = LocalDate.now();
        when(dartRepo.findByStockCodeAndStatus(eq(HYUNDAI), eq(DartControllingFinancial.STATUS_OK))).thenReturn(List.of(
                report(2026, "11012", "48561.97", "61555.97", "1223300.00", now.minusDays(47)),
                report(2025, "11011", "94459.87", "81500.00", "1150000.00", now.minusDays(196))));
    }

    @Test
    @DisplayName("재현(현대차): 지배주주 EPS·BPS 를 우선주까지 넣은 주식 수로 나눈다 — 40,039원·PER 7.5 가 30,989원·PER 9.6(네이버 30,697·9.64)")
    void controllingBasisUsesAllListedShares() {
        kisTtm();
        dartReports();

        assertThat(collector.collectStockFinancialDataSimple(HYUNDAI)).isTrue();

        assertThat(today.getEps()).as("81,465.87억 ÷ 262,883,000주").isEqualByComparingTo("30989");
        assertThat(today.getPer()).isEqualByComparingTo("9.6");
        assertThat(today.getBps()).as("1,223,300억 ÷ 262,883,000주").isEqualByComparingTo("465340");
        assertThat(today.getPbr()).isEqualByComparingTo("0.64");
        assertThat(today.getPerBasis()).isEqualTo("CTRL");
        assertThat(today.getMarketCap()).as("시가총액은 그 코드의 값 그대로").isEqualByComparingTo("607347");
    }

    @Test
    @DisplayName("ROE 는 순이익 ÷ 자본이라 주식 수와 무관하다")
    void roeIsUnchanged() {
        kisTtm();
        dartReports();

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getRoe()).as("81,465.87 ÷ 1,223,300").isEqualByComparingTo("6.66");
    }

    @Test
    @DisplayName("DART 지배주주 값이 없는 회사(KIS 연결 기준)도 같은 분모를 쓴다")
    void consolidatedBasisUsesAllListedShares() {
        kisTtm();
        when(dartRepo.findByStockCodeAndStatus(eq(HYUNDAI), eq(DartControllingFinancial.STATUS_OK))).thenReturn(List.of());

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getPerBasis()).isEqualTo("CONSOL");
        assertThat(today.getEps()).as("90,000억 ÷ 262,883,000주").isEqualByComparingTo("34236");
        assertThat(today.getBps()).as("1,300,000억 ÷ 262,883,000주").isEqualByComparingTo("494517");
    }

    @Test
    @DisplayName("자본잠식이면 BPS 도 같은 분모로 음수 그대로 남긴다")
    void impairedCapitalUsesAllListedShares() {
        Map<String, BigDecimal> ratios = new HashMap<>();
        ratios.put("totalEquity", new BigDecimal("-2628.83"));
        doReturn(ratios).when(collector).getFinancialRatios(anyString(), anyString());
        when(dartRepo.findByStockCodeAndStatus(eq(HYUNDAI), eq(DartControllingFinancial.STATUS_OK))).thenReturn(List.of());

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getBps()).as("−2,628.83억 ÷ 262,883,000주").isEqualByComparingTo("-1000");
        assertThat(today.getPbr()).isNull();
    }

    @Test
    @DisplayName("우선주가 없는 회사는 그대로다")
    void companyWithoutPreferredIsUnchanged() {
        when(status.preferredListedShares(HYUNDAI)).thenReturn(0L);
        kisTtm();
        dartReports();

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getEps()).as("81,465.87억 ÷ 203,466,000주").isEqualByComparingTo("40039");
        assertThat(today.getPer()).isEqualByComparingTo("7.5");
    }

    @Test
    @DisplayName("주식 수를 아직 못 읽었으면(재시작 직후) 종전대로 보통주만 — 있던 분모로 계산하고 회차 요약이 말한다")
    void notLoadedFallsBackToCommonShares() {
        when(status.isPreferredSharesLoaded()).thenReturn(false);
        when(status.preferredListedShares(HYUNDAI)).thenReturn(0L);
        kisTtm();
        dartReports();
        collector.drainStateSummary();

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getEps()).isEqualByComparingTo("40039");
        assertThat(collector.drainStateSummary()).contains("우선주 주식 수 미수신");
    }

    @Test
    @DisplayName("KIS 가 상장주식수를 안 주면 우선주만으로 나누지 않는다 — KIS 값(최근 결산) 그대로")
    void noCommonSharesKeepsKisValues() throws Exception {
        when(kis.getStockPrice(HYUNDAI)).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"현대차\",\"stck_prpr\":\"298500\",\"hts_avls\":\"607347\","
                        + "\"per\":\"9.5\",\"pbr\":\"0.64\",\"eps\":\"31300\",\"bps\":\"470000\",\"lstn_stcn\":\"0\"}}"));
        kisTtm();
        dartReports();

        collector.collectStockFinancialDataSimple(HYUNDAI);

        assertThat(today.getEps()).isEqualByComparingTo("31300");
        assertThat(today.getBps()).isEqualByComparingTo("470000");
    }
}
