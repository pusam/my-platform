package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 성장률 배치(2026-09-29) — "전년 대비"는 <b>분기 원본 TTM(최근 4분기 합)끼리</b> 비교한다.
 *
 * <p>고치기 전 배치는 최신 일별 행(TTM)을 "1년 전 ±30일" 아무 행과 비교했다 — 그 행이 한 분기짜리이거나
 * 옛 단위 오류 행이면 매출이 +300% 가 되고, 1년 전 행이 없으면 30일 전 행을 "YoY"로 썼다.
 * 운영 실측(9/28): 매출 성장률이 채워진 2,156종목 중 <b>2,048종목이 ±200% 초과</b>. 베뉴지 매출 +371% 는
 * 실제로 +9.3% 다. 그리고 값이 0 일 때만 채워서 한 번 들어간 엉터리 값은 그 행에서 고쳐지지 않았다.
 */
class GrowthRateBatchTest {

    private StockFinancialDataRepository dailyRepo;
    private StockQuarterlyFinancialRepository quarterlyRepo;
    private KoreaInvestmentService kis;
    private StockFinancialDataCollector collector;

    @BeforeEach
    void setUp() {
        dailyRepo = mock(StockFinancialDataRepository.class);
        quarterlyRepo = mock(StockQuarterlyFinancialRepository.class);
        kis = mock(KoreaInvestmentService.class);
        collector = new StockFinancialDataCollector(dailyRepo, quarterlyRepo, kis,
                mock(RestTemplate.class), new ObjectMapper(), mock(StockMasterService.class),
                mock(com.myplatform.backend.dartfinancial.DartControllingFinancialRepository.class));
        when(dailyRepo.findByStockCodeOrderByReportDateDesc(anyString())).thenReturn(List.of());
    }

    /** 오늘 이전의 가장 가까운 분기말(3/6/9/12월) 기준 상대 분기 — 테스트가 날짜와 함께 썩지 않게. */
    static StockQuarterlyFinancial q(String code, int quartersAgo, String rev, String op, String net, boolean cumulative) {
        YearMonth ym = YearMonth.from(LocalDate.now()).minusMonths(1);
        while (ym.getMonthValue() % 3 != 0) ym = ym.minusMonths(1);
        ym = ym.minusMonths(3L * quartersAgo);
        return StockQuarterlyFinancial.builder().stockCode(code)
                .fiscalPeriod(String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .periodEnd(ym.atEndOfMonth()).cumulative(cumulative)
                .revenue(rev == null ? null : new BigDecimal(rev))
                .operatingProfit(op == null ? null : new BigDecimal(op))
                .netIncome(net == null ? null : new BigDecimal(net)).build();
    }

    /** 베뉴지(019010) 누적 원본 그대로 — 2024-03 ~ 2026-06 (stock_quarterly_financial, 2026-09-28 수집). */
    static List<StockQuarterlyFinancial> venueG() {
        String[][] rows = {
                {"82", "18", "2"}, {"226", "88", "60"}, {"326", "116", "-63"}, {"479", "184", "-113"},
                {"91", "21", "113"}, {"208", "77", "220"}, {"344", "103", "543"}, {"482", "150", "1011"},
                {"104", "19", "579"}, {"230", "73", "2773"}};
        List<StockQuarterlyFinancial> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(q("019010", rows.length - 1 - i, rows[i][0], rows[i][1], rows[i][2], true));
        }
        return out;
    }

    /** 고치기 전 배치가 남긴 엉터리 값을 그대로 가진 오늘 행. */
    static StockFinancialData latestRow(String code, String per) {
        StockFinancialData d = new StockFinancialData();
        d.setStockCode(code);
        d.setReportDate(LocalDate.now());
        d.setMarketCap(new BigDecimal("5000"));   // KIS 일별 행은 시총이 값 또는 0 — NULL 은 네이버 분기 행
        d.setPer(new BigDecimal(per));
        d.setNetIncome(new BigDecimal("3564"));
        d.setRevenueGrowth(new BigDecimal("371.03"));
        d.setProfitGrowth(new BigDecimal("1006.83"));
        d.setEpsGrowth(new BigDecimal("1037.54"));
        d.setPeg(new BigDecimal("0.01"));
        return d;
    }

    @Test
    @DisplayName("실측 베뉴지 — 매출은 TTM 504억 vs 1년 전 TTM 461억 = +9.33% (고치기 전 +371%)")
    void venueGRevenueGrowthIsTtmYearOverYear() {
        StockFinancialData row = latestRow("019010", "0.7");
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(row));
        when(quarterlyRepo.findAllSince(any())).thenReturn(venueG());

        collector.calculateAndUpdateGrowthRates();

        assertThat(row.getRevenueGrowth()).isEqualByComparingTo("9.33");
        // 순이익은 1년 전 TTM 47억 → 3,564억 — 정의대로의 값(영업 밖 이익이 대부분이지만 그건 성장률 정의 밖의 문제)
        assertThat(row.getProfitGrowth()).isEqualByComparingTo("7482.98");
        assertThat(row.getEpsGrowth()).as("EPS 성장률 = TTM 순이익 성장률(주식 수 변화 미반영)").isEqualByComparingTo("7482.98");
        assertThat(row.getPeg()).as("성장률 200% 초과는 기저효과 — PEG 를 만들지 않는다").isNull();
    }

    @Test
    @DisplayName("1년 전 TTM 순이익이 적자면 순이익 성장률·PEG 는 모름 — 적자를 분모로 한 변화율은 성장률이 아니다")
    void lossBaseLeavesProfitGrowthUnknown() {
        List<StockQuarterlyFinancial> rows = new ArrayList<>();
        String[] ago = {"-10", "-20", "-5", "-15"};     // 1년 전 4분기 순이익 합 -50
        String[] now = {"30", "40", "50", "60"};
        for (int i = 0; i < 4; i++) rows.add(q("000001", 7 - i, "1000", "50", ago[i], false));
        for (int i = 0; i < 4; i++) rows.add(q("000001", 3 - i, "1100", "60", now[i], false));
        StockFinancialData row = latestRow("000001", "10");
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(row));
        when(quarterlyRepo.findAllSince(any())).thenReturn(rows);

        collector.calculateAndUpdateGrowthRates();

        assertThat(row.getRevenueGrowth()).isEqualByComparingTo("10.00");
        assertThat(row.getProfitGrowth()).isNull();
        assertThat(row.getEpsGrowth()).isNull();
        assertThat(row.getPeg()).isNull();
    }

    @Test
    @DisplayName("정상 성장 — PEG = PER / 순이익 성장률")
    void normalGrowthComputesPeg() {
        List<StockQuarterlyFinancial> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(q("000002", 7 - i, "1000", "100", "100", false));   // 1년 전 TTM 순이익 400
        for (int i = 0; i < 4; i++) rows.add(q("000002", 3 - i, "1200", "120", "125", false));   // 지금 TTM 500 (+25%)
        StockFinancialData row = latestRow("000002", "10");
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(row));
        when(quarterlyRepo.findAllSince(any())).thenReturn(rows);

        collector.calculateAndUpdateGrowthRates();

        assertThat(row.getRevenueGrowth()).isEqualByComparingTo("20.00");
        assertThat(row.getProfitGrowth()).isEqualByComparingTo("25.00");
        assertThat(row.getPeg()).isEqualByComparingTo("0.40");
    }

    @Test
    @DisplayName("분기가 모자라면(1년 전 4분기 중 결측) 모름 — 30일 전 행으로 대신하지 않고, 남아 있던 엉터리 값도 지운다")
    void missingQuarterClearsStaleGarbage() {
        List<StockQuarterlyFinancial> rows = new ArrayList<>();
        rows.add(q("000003", 6, "1000", "100", "100", false));   // 7분기 전 결측 — 1년 전 창이 불완전
        rows.add(q("000003", 5, "1000", "100", "100", false));
        rows.add(q("000003", 4, "1000", "100", "100", false));
        for (int i = 0; i < 4; i++) rows.add(q("000003", 3 - i, "1200", "120", "125", false));
        StockFinancialData row = latestRow("000003", "10");
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(row));
        when(quarterlyRepo.findAllSince(any())).thenReturn(rows);

        collector.calculateAndUpdateGrowthRates();

        assertThat(row.getRevenueGrowth()).isNull();
        assertThat(row.getProfitGrowth()).isNull();
        assertThat(row.getEpsGrowth()).isNull();
        assertThat(row.getPeg()).isNull();
    }

    @Test
    @DisplayName("분기 원본이 아예 없는 종목도 엉터리 값을 남기지 않는다")
    void noQuarterlyDataClearsGarbage() {
        StockFinancialData row = latestRow("000004", "10");
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(row));
        when(quarterlyRepo.findAllSince(any())).thenReturn(List.of());

        collector.calculateAndUpdateGrowthRates();

        assertThat(row.getRevenueGrowth()).isNull();
        assertThat(row.getProfitGrowth()).isNull();
        assertThat(row.getPeg()).isNull();
    }
    @Test
    @DisplayName("네이버 분기 행(market_cap NULL)이 최신이어도 거기 쓰지 않는다 — 그 아래 KIS 일별 행에 쓴다")
    void naverQuarterRowIsNeverTheTarget() {
        StockFinancialData naver = new StockFinancialData();
        naver.setStockCode("000002");
        naver.setReportDate(LocalDate.now());          // 12-31 추정치 행이 그날이 지나 '최신'이 된 상황
        naver.setNetIncome(new BigDecimal("999"));
        naver.setProfitGrowth(new BigDecimal("55.00"));
        StockFinancialData kisRow = latestRow("000002", "10");
        kisRow.setReportDate(LocalDate.now().minusDays(1));
        List<StockQuarterlyFinancial> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(q("000002", 7 - i, "1000", "100", "100", false));
        for (int i = 0; i < 4; i++) rows.add(q("000002", 3 - i, "1200", "120", "125", false));
        when(dailyRepo.findAllRecentData(any())).thenReturn(List.of(naver, kisRow));   // report_date 내림차순
        when(quarterlyRepo.findAllSince(any())).thenReturn(rows);

        collector.calculateAndUpdateGrowthRates();

        assertThat(naver.getProfitGrowth()).as("네이버 행은 writer 가 다르다 — 그대로").isEqualByComparingTo("55.00");
        assertThat(naver.getRevenueGrowth()).isNull();
        assertThat(kisRow.getProfitGrowth()).isEqualByComparingTo("25.00");
        assertThat(kisRow.getPeg()).isEqualByComparingTo("0.40");
    }

    @Test
    @DisplayName("1단계(KIS 수집)는 성장률 4종을 건드리지 않는다 — 15:38 회차가 08:30 회차의 계산값을 KIS 죽은 필드 0 으로 덮던 경로")
    void step1CollectionLeavesGrowthAlone() throws Exception {
        StockFinancialData today = latestRow("019010", "0.7");
        today.setRevenueGrowth(new BigDecimal("9.33"));
        today.setProfitGrowth(new BigDecimal("25.00"));
        today.setEpsGrowth(new BigDecimal("25.00"));
        today.setPeg(new BigDecimal("0.40"));
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(java.util.Optional.of(today));
        when(kis.getStockPrice("019010")).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"베뉴지\",\"stck_prpr\":\"10000\","
                        + "\"hts_avls\":\"1000\",\"per\":\"10\",\"pbr\":\"1\",\"eps\":\"1000\","
                        + "\"bps\":\"10000\",\"lstn_stcn\":\"10000000\"}}"));
        when(kis.getAccessToken()).thenReturn("token");
        StockFinancialDataCollector spy = org.mockito.Mockito.spy(collector);
        // 고치기 전 재무비율 파서가 KIS eps_cagr·sls_cagr·ntin_cagr(실측 전부 0)를 이 키로 넘겼다
        org.mockito.Mockito.doReturn(new java.util.HashMap<>(java.util.Map.of(
                "epsGrowth", BigDecimal.ZERO, "revenueGrowth", BigDecimal.ZERO, "profitGrowth", BigDecimal.ZERO)))
                .when(spy).getFinancialRatios(anyString(), anyString());

        assertThat(spy.collectStockFinancialDataSimple("019010")).isTrue();

        assertThat(today.getRevenueGrowth()).isEqualByComparingTo("9.33");
        assertThat(today.getProfitGrowth()).isEqualByComparingTo("25.00");
        assertThat(today.getEpsGrowth()).isEqualByComparingTo("25.00");
        assertThat(today.getPeg()).isEqualByComparingTo("0.40");
    }
}
