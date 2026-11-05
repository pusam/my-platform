package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.DartCompanyRepository;
import com.myplatform.backend.dartfinancial.DartControllingFinancial;
import com.myplatform.backend.dartfinancial.DartControllingFinancialRepository;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DART 로 채운 분기를 일별 재무 행에 쓴다 — {@code collectStockFinancialDataSimple}(2026-11-05).
 *
 * <p>별도재무제표 회사는 KIS 가 분기를 주지 않아 매출·영업이익·순이익·영업이익률·부채비율이 비어 있었다(11/4 V68 뒤 약 600종목).
 * 그 칸이 비면 실적 축 20점·마법의 공식·PEG·가치 점수의 부채 4점을 받을 수 없다.
 */
class FinancialDartQuarterFillTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private StockFinancialDataCollector collector;
    private StockQuarterlyFinancialRepository quarterRepo;
    private DartControllingFinancialRepository dartRepo;
    private StockFinancialData today;
    private final Map<String, BigDecimal> kis = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        StockFinancialDataRepository dailyRepo = mock(StockFinancialDataRepository.class);
        dartRepo = mock(DartControllingFinancialRepository.class);
        quarterRepo = mock(StockQuarterlyFinancialRepository.class);
        KoreaInvestmentService kisService = mock(KoreaInvestmentService.class);
        collector = spy(new StockFinancialDataCollector(dailyRepo, quarterRepo, kisService,
                mock(RestTemplate.class), new ObjectMapper(), mock(StockMasterService.class), dartRepo,
                mock(DartCompanyRepository.class)));
        collector.clock = Clock.fixed(LocalDate.of(2026, 11, 5).atTime(8, 30).atZone(KST).toInstant(), KST);
        today = new StockFinancialData();
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(today));
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());
        when(kisService.getAccessToken()).thenReturn("token");
        when(kisService.getStockPrice("058470")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"리노공업\",\"stck_prpr\":\"55000\",\"hts_avls\":\"41900\","
                        + "\"per\":\"20\",\"pbr\":\"5\",\"eps\":\"2750\",\"bps\":\"11000\",\"lstn_stcn\":\"76000000\"}}"));
        doReturn(kis).when(collector).getFinancialRatios(anyString(), anyString());
    }

    private static StockQuarterlyFinancial row(String period, String revenue, String operating, String net, String source) {
        return StockQuarterlyFinancial.builder().stockCode("058470").fiscalPeriod(period)
                .periodEnd(YearMonth.parse(period.substring(0, 4) + "-" + period.substring(4)).atEndOfMonth())
                .revenue(new BigDecimal(revenue)).operatingProfit(new BigDecimal(operating)).netIncome(new BigDecimal(net))
                .source(source).build();
    }

    private void dartQuarters() {
        when(quarterRepo.findByStockCodeOrderByPeriodEndDesc("058470")).thenReturn(List.of(
                row("202606", "1200", "600", "500", StockQuarterlyFinancial.SOURCE_DART),
                row("202603", "1100", "550", "480", StockQuarterlyFinancial.SOURCE_DART),
                row("202512", "1000", "500", "450", StockQuarterlyFinancial.SOURCE_DART),
                row("202509", "946", "445", "448", StockQuarterlyFinancial.SOURCE_DART)));
    }

    private DartControllingFinancial latestReport() {
        return DartControllingFinancial.builder().stockCode("058470").bsnsYear(2026).reprtCode("11012").fsDiv("OFS")
                .status(DartControllingFinancial.STATUS_OK).filedOn(LocalDate.of(2026, 8, 14)).incomeParsed(true)
                .totalEquity(new BigDecimal("8000")).totalAssets(new BigDecimal("9000"))
                .totalLiabilities(new BigDecimal("1000")).build();
    }

    @Test
    @DisplayName("리노공업처럼 KIS 분기가 없는 회사 — DART 4분기로 매출·영업이익·순이익·영업이익률을 저장한다")
    void dartQuartersFillIncome() {
        dartQuarters();

        assertThat(collector.collectStockFinancialDataSimple("058470")).isTrue();

        assertThat(today.getRevenue()).isEqualByComparingTo("4246");
        assertThat(today.getOperatingProfit()).isEqualByComparingTo("2095");
        assertThat(today.getNetIncome()).isEqualByComparingTo("1878");
        assertThat(today.getOperatingMargin()).isEqualByComparingTo("49.34");
    }

    @Test
    @DisplayName("보고서 비교 칸으로 만든 4분기가 분기 행 합보다 먼저다 — 연중 연결 전환·재분류에도 맞다")
    void reportsWinOverQuarterRows() {
        dartQuarters();
        when(dartRepo.findByStockCodeAndStatus("058470", DartControllingFinancial.STATUS_OK)).thenReturn(List.of(
                DartControllingFinancial.builder().bsnsYear(2025).reprtCode("11011").fsDiv("CFS").incomeParsed(true)
                        .revenue(new BigDecimal("13710.57")).operatingProfit(new BigDecimal("1300.66")).totalNetIncome(new BigDecimal("1738")).build(),
                DartControllingFinancial.builder().bsnsYear(2026).reprtCode("11012").fsDiv("CFS").incomeParsed(true)
                        .filedOn(LocalDate.of(2026, 8, 14)).status(DartControllingFinancial.STATUS_OK)
                        .revenue(new BigDecimal("7074.59")).operatingProfit(new BigDecimal("892.90")).totalNetIncome(new BigDecimal("861"))
                        .revenuePrev(new BigDecimal("7058.82")).operatingProfitPrev(new BigDecimal("560.88")).totalNetIncomePrev(new BigDecimal("500")).build()));

        collector.collectStockFinancialDataSimple("058470");

        assertThat(today.getRevenue()).isEqualByComparingTo("13726.34");
        assertThat(today.getOperatingProfit()).isEqualByComparingTo("1632.68");
    }

    @Test
    @DisplayName("KIS 재무상태표가 없으면 DART 최신 보고서의 자본·자산·부채로 — 부채비율 4점을 잃지 않는다")
    void dartBalanceFillsDebtRatio() {
        dartQuarters();
        when(dartRepo.findByStockCodeAndStatus("058470", DartControllingFinancial.STATUS_OK)).thenReturn(List.of(latestReport()));

        collector.collectStockFinancialDataSimple("058470");

        assertThat(today.getTotalEquity()).isEqualByComparingTo("8000");
        assertThat(today.getTotalDebt()).isEqualByComparingTo("1000");
        assertThat(today.getDebtRatio()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("DART 분기가 있으면 KIS 묵은 재무상태표보다 DART 최신 보고서가 우선이다")
    void dartBalanceWinsWhenFilled() {
        dartQuarters();
        when(dartRepo.findByStockCodeAndStatus("058470", DartControllingFinancial.STATUS_OK)).thenReturn(List.of(latestReport()));
        kis.put("totalEquity", new BigDecimal("3000"));
        kis.put("debtRatio", new BigDecimal("90"));

        collector.collectStockFinancialDataSimple("058470");

        assertThat(today.getTotalEquity()).isEqualByComparingTo("8000");
        assertThat(today.getDebtRatio()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("DART 행이 없는 연결 회사는 종전대로 KIS 값 — DART 재무상태표를 묻지도 않는다")
    void kisPathUntouched() {
        when(quarterRepo.findByStockCodeOrderByPeriodEndDesc("058470")).thenReturn(List.of(
                row("202606", "1200", "600", "500", StockQuarterlyFinancial.SOURCE_KIS)));
        kis.put("revenue", new BigDecimal("777"));
        kis.put("totalEquity", new BigDecimal("3000"));
        kis.put("debtRatio", new BigDecimal("90"));

        collector.collectStockFinancialDataSimple("058470");

        assertThat(today.getRevenue()).isEqualByComparingTo("777");
        assertThat(today.getTotalEquity()).isEqualByComparingTo("3000");
        assertThat(today.getDebtRatio()).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("§4c — DART 분기가 묵었으면(최신 2025-12, 200일 초과) 옛 4분기를 오늘 값으로 쓰지 않는다")
    void staleDartQuartersNotUsed() {
        when(quarterRepo.findByStockCodeOrderByPeriodEndDesc("058470")).thenReturn(List.of(
                row("202512", "1000", "500", "450", StockQuarterlyFinancial.SOURCE_DART),
                row("202509", "946", "445", "448", StockQuarterlyFinancial.SOURCE_DART),
                row("202506", "900", "400", "400", StockQuarterlyFinancial.SOURCE_DART),
                row("202503", "800", "300", "300", StockQuarterlyFinancial.SOURCE_DART)));

        collector.collectStockFinancialDataSimple("058470");

        assertThat(today.getRevenue()).isNull();
        assertThat(today.getNetIncome()).isNull();
    }

    @Test
    @DisplayName("KIS 수집이 DART 행을 덮지 않는다 — 그 회사의 KIS 행은 0·0·0 이다")
    void kisWriterSkipsDartRows() throws Exception {
        StockQuarterlyFinancial dartRow = row("202606", "1200", "600", "500", StockQuarterlyFinancial.SOURCE_DART);
        when(quarterRepo.findByStockCodeAndFiscalPeriod("058470", "202606")).thenReturn(Optional.of(dartRow));
        when(quarterRepo.findByStockCodeAndFiscalPeriod("058470", "202603")).thenReturn(Optional.empty());

        collector.persistQuarterlyRows("058470", new ObjectMapper().readTree(
                "[{\"stac_yymm\":\"202606\",\"sale_account\":\"0\",\"bsop_prti\":\"0\",\"thtr_ntin\":\"0\"},"
                        + "{\"stac_yymm\":\"202603\",\"sale_account\":\"10\",\"bsop_prti\":\"5\",\"thtr_ntin\":\"4\"}]"), true);

        verify(quarterRepo, never()).save(eq(dartRow));
        assertThat(dartRow.getRevenue()).isEqualByComparingTo("1200");
        assertThat(dartRow.isDart()).isTrue();
    }
}
