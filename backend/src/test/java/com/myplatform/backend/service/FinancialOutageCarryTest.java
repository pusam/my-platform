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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KIS 재무 호출 자체가 실패하는 날(2026-11-12~13 운영 실측) — 그 종목이 마지막으로 받아 둔 값을 한도 안에서 이어 쓴다.
 *
 * <p>재현: 11/12 08:30 부터 재무비율 주소는 404, 손익계산서·재무상태표는 '호출 전처리 오류'가 전 종목에서 났다(우리 요청은 그대로 —
 * 공식 샘플과 같고, 키 없이 불러도 재무비율 주소만 404). 그날부터 일별 행 2,602개 중 매출·영업이익·순이익은 533, 부채비율은 578 개만
 * 남았고(전날 2,498·2,564) PER 기준 233종목이 CONSOL → KIS 로 돌아갔다. 수집은 '성공 2602, 실패 0'으로 끝나 아무도 몰랐다.
 *
 * <p>재무제표 값은 분기에 한 번 바뀐다 — 호출이 실패한 날은 모르는 날이 아니라 '어제와 같은 값'인 날이다. 그래서 <b>호출이 실패했을
 * 때만</b>(KIS 가 '자료 없음·묵음'이라고 답한 것은 그대로 모른다) 직전 일별 행의 값을 쓰고, 처음 받은 날을
 * {@code statement_carried_from} 에 남긴다. 30일이 지나면 다시 모른다.
 */
class FinancialOutageCarryTest {

    private static final LocalDate TODAY = LocalDate.now();

    private StockFinancialDataRepository dailyRepo;
    private RestTemplate rest;
    private StockFinancialDataCollector collector;

    @BeforeEach
    void setUp() throws Exception {
        dailyRepo = mock(StockFinancialDataRepository.class);
        rest = mock(RestTemplate.class);
        DartControllingFinancialRepository dartRepo = mock(DartControllingFinancialRepository.class);
        KoreaInvestmentService kis = mock(KoreaInvestmentService.class);
        collector = spy(new StockFinancialDataCollector(dailyRepo, mock(StockQuarterlyFinancialRepository.class), kis,
                rest, new ObjectMapper(), mock(StockMasterService.class), dartRepo, mock(DartCompanyRepository.class)));
        when(dartRepo.findByStockCodeAndStatus(anyString(), anyString())).thenReturn(List.of());
        when(kis.getAccessToken()).thenReturn("token");
        when(kis.getStockPrice(anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"rt_cd\":\"0\",\"output\":{\"hts_kor_isnm\":\"GS\",\"stck_prpr\":\"105000\",\"hts_avls\":\"97819\","
                        + "\"per\":\"4.20\",\"pbr\":\"0.60\",\"eps\":\"25000\",\"bps\":\"175000\",\"lstn_stcn\":\"92915378\"}}"));
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.empty());
    }

    /** KIS 재무 호출 결과 — failed 면 '호출 실패' 표시만 담긴다(값 없음). */
    private void kisStatements(boolean failed) {
        Map<String, BigDecimal> r = new HashMap<>();
        if (failed) {
            r.put(StockFinancialDataCollector.KEY_INCOME_CALL_FAILED, BigDecimal.ONE);
            r.put(StockFinancialDataCollector.KEY_BALANCE_CALL_FAILED, BigDecimal.ONE);
        }
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());
    }

    /** 며칠 전 일별 행 — 그날 KIS 가 준 값. */
    private static StockFinancialData earlierRow(LocalDate date, LocalDate carriedFrom) {
        StockFinancialData row = new StockFinancialData();
        row.setReportDate(date);
        row.setMarketCap(new BigDecimal("97819"));
        row.setNetIncome(new BigDecimal("30000"));
        row.setRevenue(new BigDecimal("500000"));
        row.setOperatingProfit(new BigDecimal("40000"));
        row.setTotalEquity(new BigDecimal("210252"));
        row.setTotalAssets(new BigDecimal("378413"));
        row.setTotalDebt(new BigDecimal("168161"));
        row.setDebtRatio(new BigDecimal("79.98"));
        row.setStatementCarriedFrom(carriedFrom);
        return row;
    }

    private void previousRows(StockFinancialData... rows) {
        when(dailyRepo.findTop10ByStockCodeAndReportDateBeforeAndMarketCapIsNotNullOrderByReportDateDesc(anyString(), any()))
                .thenReturn(List.of(rows));
    }

    private StockFinancialData saved() {
        ArgumentCaptor<StockFinancialData> captor = ArgumentCaptor.forClass(StockFinancialData.class);
        verify(dailyRepo).save(captor.capture());
        return captor.getValue();
    }

    private static StockFinancialData emptyRow(LocalDate date) {
        StockFinancialData row = new StockFinancialData();
        row.setReportDate(date);
        row.setMarketCap(new BigDecimal("97819"));
        return row;
    }

    @Test
    @DisplayName("재현: KIS 재무 호출이 실패한 날은 직전에 받아 둔 값으로 채운다 — 부채비율·영업이익률이 비지 않고 PER 기준도 KIS 연간으로 돌아가지 않는다")
    void apiFailureCarriesLastReceivedValues() {
        kisStatements(true);
        // 11/12·11/13 행은 비어 있고(장애) 11/11 행이 마지막으로 받은 값이다
        LocalDate received = TODAY.minusDays(5);
        previousRows(emptyRow(TODAY.minusDays(1)), emptyRow(TODAY.minusDays(4)), earlierRow(received, null));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        StockFinancialData row = saved();
        assertThat(row.getNetIncome()).isEqualByComparingTo("30000");
        assertThat(row.getRevenue()).isEqualByComparingTo("500000");
        assertThat(row.getOperatingProfit()).isEqualByComparingTo("40000");
        assertThat(row.getOperatingMargin()).isEqualByComparingTo("8.00");
        assertThat(row.getTotalEquity()).isEqualByComparingTo("210252");
        assertThat(row.getDebtRatio()).isEqualByComparingTo("79.98");
        assertThat(row.getPerBasis()).isEqualTo("CONSOL");
        // 오늘 가격 105,000 ÷ (30,000억 ÷ 92,915,378주 = 32,287원) = 3.3 — 가격은 오늘, 실적은 받아 둔 값
        assertThat(row.getPer()).isEqualByComparingTo("3.3");
        assertThat(row.getPbr()).isEqualByComparingTo("0.46");
        assertThat(row.getStatementCarriedFrom()).isEqualTo(received);
    }

    @Test
    @DisplayName("KIS 가 답은 했는데 자료가 없거나 묵은 것(호출 실패 아님)은 이어 쓰지 않는다 — 모르는 것은 모른다")
    void answeredButEmptyIsNotCarried() {
        kisStatements(false);
        previousRows(earlierRow(TODAY.minusDays(1), null));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        StockFinancialData row = saved();
        assertThat(row.getNetIncome()).isNull();
        assertThat(row.getTotalEquity()).isNull();
        assertThat(row.getDebtRatio()).isNull();
        assertThat(row.getPerBasis()).isEqualTo("KIS");
        assertThat(row.getStatementCarriedFrom()).isNull();
    }

    @Test
    @DisplayName("이어 쓴 행을 다음 날 또 이어 쓸 때 기준일은 처음 받은 날이다 — 날마다 하루씩 새 값인 척하지 않는다")
    void chainedCarryKeepsTheOriginalDate() {
        kisStatements(true);
        LocalDate received = TODAY.minusDays(9);
        previousRows(earlierRow(TODAY.minusDays(1), received));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(saved().getStatementCarriedFrom()).isEqualTo(received);
    }

    @Test
    @DisplayName("처음 받은 날이 한도(30일)보다 오래되면 이어 쓰지 않는다 — 다시 모른다")
    void tooOldIsNotCarried() {
        kisStatements(true);
        previousRows(earlierRow(TODAY.minusDays(1), TODAY.minusDays(31)));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        StockFinancialData row = saved();
        assertThat(row.getNetIncome()).isNull();
        assertThat(row.getDebtRatio()).isNull();
        assertThat(row.getStatementCarriedFrom()).isNull();
        assertThat(collector.drainStateSummary()).contains("묵어");
    }

    @Test
    @DisplayName("한도 0 이면 끈 것이다 — 예전처럼 못 받은 날은 비운다")
    void zeroMaxDaysTurnsItOff() {
        kisStatements(true);
        collector.statementCarryMaxDays = 0;
        previousRows(earlierRow(TODAY.minusDays(1), null));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(saved().getNetIncome()).isNull();
    }

    @Test
    @DisplayName("같은 날 두 번째 회차도 실패하면 아침에 이어 쓴 값과 그 기준일을 그대로 둔다")
    void secondRunOfTheDayKeepsTheMorningCarry() {
        kisStatements(true);
        LocalDate received = TODAY.minusDays(2);
        StockFinancialData morning = earlierRow(TODAY, received);
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(morning));
        previousRows(emptyRow(TODAY.minusDays(1)));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(morning.getNetIncome()).isEqualByComparingTo("30000");
        assertThat(morning.getDebtRatio()).isEqualByComparingTo("79.98");
        assertThat(morning.getStatementCarriedFrom()).isEqualTo(received);
    }

    @Test
    @DisplayName("오늘 값을 받으면 기준일 표시는 지워진다")
    void freshValuesClearTheMark() {
        Map<String, BigDecimal> r = new HashMap<>();
        r.put("netIncome", new BigDecimal("33000"));
        r.put("revenue", new BigDecimal("520000"));
        r.put("operatingProfit", new BigDecimal("41600"));
        r.put("totalEquity", new BigDecimal("220000"));
        doReturn(r).when(collector).getFinancialRatios(anyString(), anyString());
        StockFinancialData morning = earlierRow(TODAY, TODAY.minusDays(2));
        when(dailyRepo.findByStockCodeAndReportDate(anyString(), any())).thenReturn(Optional.of(morning));

        assertThat(collector.collectStockFinancialDataSimple("078930")).isTrue();

        assertThat(morning.getNetIncome()).isEqualByComparingTo("33000");
        assertThat(morning.getStatementCarriedFrom()).isNull();
    }

    // ---------- 호출 실패를 '자료 없음'과 구분해 알린다 ----------

    @Test
    @DisplayName("주소가 404 거나 KIS 가 오류로 답하면 '호출 실패'로 표시한다 — 빈 응답과 구분된다")
    void failedCallsAreMarked() {
        when(rest.exchange(contains("financial-ratio"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND, "Not Found",
                        new org.springframework.http.HttpHeaders(), new byte[0], null));
        when(rest.exchange(contains("income-statement"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"msg1\":\"호출 전처리 오류 입니다.\"}"));
        when(rest.exchange(contains("balance-sheet"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"1\",\"msg1\":\"호출 전처리 오류 입니다.\"}"));

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "078930");

        assertThat(ratios).containsKeys(StockFinancialDataCollector.KEY_INCOME_CALL_FAILED,
                StockFinancialDataCollector.KEY_BALANCE_CALL_FAILED);
    }

    @Test
    @DisplayName("KIS 가 정상 응답으로 빈 목록을 주면 호출 실패가 아니다")
    void emptyAnswersAreNotFailures() {
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"rt_cd\":\"0\",\"output\":[]}"));

        Map<String, BigDecimal> ratios = collector.getFinancialRatios("token", "078930");

        assertThat(ratios).doesNotContainKeys(StockFinancialDataCollector.KEY_INCOME_CALL_FAILED,
                StockFinancialDataCollector.KEY_BALANCE_CALL_FAILED);
    }

    // ---------- 전 종목이 실패하는 회차는 호출을 멈춘다 ----------

    @Test
    @DisplayName("재무 호출이 연속 30종목 실패하면 그 회차의 나머지 종목은 부르지 않는다 — 회차가 끝나면 다시 시도한다")
    void consecutiveFailuresStopTheCallsForTheRun() {
        kisStatements(true);
        previousRows();

        for (int i = 0; i < StockFinancialDataCollector.STATEMENT_FAILURE_TRIP; i++) {
            collector.collectStockFinancialDataSimple("078930");
        }
        verify(collector, times(StockFinancialDataCollector.STATEMENT_FAILURE_TRIP)).getFinancialRatios(anyString(), anyString());
        assertThat(collector.statementCallsSuspended()).isTrue();

        collector.collectStockFinancialDataSimple("078930");
        verify(collector, times(StockFinancialDataCollector.STATEMENT_FAILURE_TRIP)).getFinancialRatios(anyString(), anyString());

        assertThat(collector.drainStateSummary()).contains("건너뜀");
        assertThat(collector.statementCallsSuspended()).isFalse();
    }

    @Test
    @DisplayName("중간에 한 종목이라도 받으면 연속 실패로 세지 않는다")
    void aSuccessResetsTheStreak() {
        previousRows();
        for (int i = 0; i < StockFinancialDataCollector.STATEMENT_FAILURE_TRIP - 1; i++) {
            kisStatements(true);
            collector.collectStockFinancialDataSimple("078930");
        }
        kisStatements(false);
        collector.collectStockFinancialDataSimple("078930");
        kisStatements(true);
        collector.collectStockFinancialDataSimple("078930");

        assertThat(collector.statementCallsSuspended()).isFalse();
    }
}
