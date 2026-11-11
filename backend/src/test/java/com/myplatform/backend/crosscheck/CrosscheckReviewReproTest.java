package com.myplatform.backend.crosscheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.controlroom.DataAnomalyRules;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.BotTradingPositionRepository;
import com.myplatform.backend.repository.RecommendationSnapshotRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import com.myplatform.backend.repository.StockWatchlistRepository;
import com.myplatform.backend.repository.VirtualPortfolioRepository;
import com.myplatform.backend.service.MarketCalendarService;
import com.myplatform.backend.service.SchedulerLockService;
import com.myplatform.backend.service.StockStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 독립 검토(2026-11-11) 결함 재현 — 고치기 전엔 실패한다. */
class CrosscheckReviewReproTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final LocalDate D = LocalDate.of(2026, 11, 10);
    private static final LocalDate D1 = LocalDate.of(2026, 11, 9);

    private final RestTemplate rest = new RestTemplate();
    private MockRestServiceServer server;
    private final ExternalCrosscheckRunRepository runRepo = mock(ExternalCrosscheckRunRepository.class);
    private final ExternalCrosscheckRowRepository rowRepo = mock(ExternalCrosscheckRowRepository.class);
    private final StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
    private final StockFinancialDataRepository financial = mock(StockFinancialDataRepository.class);
    private final MarketCalendarService calendar = mock(MarketCalendarService.class);
    private final List<ExternalCrosscheckRow> rows = new ArrayList<>();
    private final List<ExternalCrosscheckRun> runs = new ArrayList<>();

    private ExternalCrosscheckService service(boolean enabled) {
        Clock clock = Clock.fixed(LocalDateTime.of(TODAY, LocalTime.of(10, 20, 40)).atZone(KST).toInstant(), KST);
        StockStatusService status = mock(StockStatusService.class);
        when(status.activeStatus(anyString())).thenReturn(StockStatusService.ActiveStatus.ACTIVE);
        SchedulerLockService lock = mock(SchedulerLockService.class);
        when(lock.tryLock(anyString(), any())).thenReturn(true);
        ExternalCrosscheckService s = new ExternalCrosscheckService(rest, new ObjectMapper(), runRepo, rowRepo, history,
                financial, mock(RecommendationSnapshotRepository.class), mock(com.myplatform.backend.repository.VirtualAccountRepository.class),
                mock(VirtualPortfolioRepository.class), mock(BotTradingPositionRepository.class), mock(StockWatchlistRepository.class),
                status, calendar, lock, clock, enabled);
        s.pauseMillis = 0;
        return s;
    }

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        when(calendar.isMarketClosed(any(LocalDate.class))).thenReturn(false);
        when(calendar.marketOpenOf(any())).thenReturn(LocalTime.of(9, 0));
        when(calendar.marketCloseOf(any())).thenReturn(LocalTime.of(15, 40));
        when(calendar.minusTradingDays(eq(TODAY), eq(1))).thenReturn(D);
        when(runRepo.save(any())).thenAnswer(inv -> {
            ExternalCrosscheckRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(7L);
            runs.add(r);
            return r;
        });
        when(rowRepo.save(any())).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(financial.findLatestMarketCaps()).thenReturn(List.of());
    }

    private void bars(String code, String d, String d1) {
        when(history.findByStockCodeOrderByTradeDateDesc(eq(code), any(Pageable.class))).thenReturn(List.of(
                StockPriceHistory.builder().stockCode(code).tradeDate(D).closePrice(new BigDecimal(d)).build(),
                StockPriceHistory.builder().stockCode(code).tradeDate(D1).closePrice(new BigDecimal(d1)).build()));
    }

    @Test
    @DisplayName("B1 — 기록 전용 EPS 행 저장 실패가 실행 전체를 죽이지 않는다")
    void perShareSaveFailureDoesNotKillRun() {
        bars("005930", "189900", "187000");
        when(history.findStockCodesByTradeDate(D)).thenReturn(List.of("005930"));
        when(financial.findTop10ByStockCodeOrderByReportDateDesc("005930")).thenReturn(List.of(
                StockFinancialData.builder().stockCode("005930").reportDate(D).marketCap(BigDecimal.ONE)
                        .eps(new BigDecimal("0.01")).bps(new BigDecimal("86000")).perBasis("CTRL").build()));
        when(rowRepo.save(argThat(r -> r != null && CrosscheckRules.ITEM_EPS.equals(r.getItem()))))
                .thenThrow(new RuntimeException("Out of range value for column 'diff_pct'"));
        server.expect(manyTimes(), anything()).andRespond(withSuccess(CrosscheckRulesTest.SAMSUNG_INTEGRATION, MediaType.APPLICATION_JSON));

        ExternalCrosscheckRun run = service(true).run("CRON");

        assertThat(run.getStatus()).isEqualTo(ExternalCrosscheckRun.STATUS_COMPLETE);
        assertThat(run.getMatched()).isEqualTo(1);
    }

    @Test
    @DisplayName("A6 — BPS 행엔 per_basis(순이익 정의)를 붙이지 않는다")
    void bpsRowHasNoPerBasis() {
        bars("005930", "189900", "187000");
        when(history.findStockCodesByTradeDate(D)).thenReturn(List.of("005930"));
        when(financial.findTop10ByStockCodeOrderByReportDateDesc("005930")).thenReturn(List.of(
                StockFinancialData.builder().stockCode("005930").reportDate(D).marketCap(BigDecimal.ONE)
                        .eps(new BigDecimal("21000")).bps(new BigDecimal("86000")).perBasis("CTRL").build()));
        server.expect(manyTimes(), anything()).andRespond(withSuccess(CrosscheckRulesTest.SAMSUNG_INTEGRATION, MediaType.APPLICATION_JSON));

        service(true).run("CRON");

        ExternalCrosscheckRow bps = rows.stream().filter(r -> CrosscheckRules.ITEM_BPS.equals(r.getItem())).findFirst().orElseThrow();
        assertThat(bps.getOurBasis()).isNull();
    }

    @Test
    @DisplayName("B7 — 응답의 종목코드가 요청과 다르면 일치로 세지 않는다")
    void wrongItemIsNotMatch() {
        bars("000660", "189900", "187000");
        when(history.findStockCodesByTradeDate(D)).thenReturn(List.of("000660"));
        server.expect(manyTimes(), anything()).andRespond(withSuccess(CrosscheckRulesTest.SAMSUNG_INTEGRATION, MediaType.APPLICATION_JSON));

        ExternalCrosscheckRun run = service(true).run("CRON");

        assertThat(run.getMatched()).isZero();
    }

    @Test
    @DisplayName("B4 — 꺼져 있으면 수동 실행도 거절한다")
    void manualRefusedWhenDisabled() {
        assertThat(service(false).startManualRun()).isNotNull();
    }

    @Test
    @DisplayName("A1 — 끝나지 못한 실행의 집계는 종목 행에서 다시 센다(이미 찾은 불일치가 0 으로 사라지지 않는다)")
    void unfinishedRunRecountsFromRows() {
        when(runRepo.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(ExternalCrosscheckRun.builder()
                .id(3L).runDate(TODAY).targetBarDate(D).status(ExternalCrosscheckRun.STATUS_ERROR)
                .startedAt(LocalDateTime.of(TODAY, LocalTime.of(10, 20))).build()));
        List<ExternalCrosscheckRow> stored = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            stored.add(ExternalCrosscheckRow.builder().runId(3L).stockCode("00000" + i).item(CrosscheckRules.ITEM_CLOSE)
                    .status(CrosscheckRules.MISMATCH).ourValue(BigDecimal.TEN).externalValue(BigDecimal.ONE).build());
        }
        when(rowRepo.findByRunIdOrderByIdAsc(anyLong())).thenReturn(stored);

        ExternalCrosscheckService.RunSummary s = service(true).recentRuns().get(0);

        assertThat(s.mismatched()).isEqualTo(3);
        assertThat(s.attempted()).isEqualTo(3);
    }

    private static DataAnomalyRules.CrosscheckRunStat stat(LocalDate run, LocalDate bar, String status, int attempted,
                                                          int matched, int mismatched, Map<String, Integer> reasons) {
        Map<String, String> byCode = new java.util.LinkedHashMap<>();
        for (int i = 0; i < matched; i++) byCode.put(String.format("M%05d", i), CrosscheckRules.MATCH);
        for (int i = 0; i < mismatched; i++) byCode.put(String.format("X%05d", i), CrosscheckRules.MISMATCH);
        int n = 0;
        for (var e : reasons.entrySet()) for (int i = 0; i < e.getValue(); i++) byCode.put(String.format("N%05d", n++), e.getKey());
        return new DataAnomalyRules.CrosscheckRunStat(run, bar, status, 40, byCode, Map.of(), false, true);
    }

    @Test
    @DisplayName("B3 — 같은 봉의 뒤 실행이 멈춰도 앞 실행이 찾은 불일치(CRITICAL)는 남는다")
    void laterAbortedRunDoesNotHideEarlierCritical() {
        var aborted = stat(TODAY, D, "ABORTED", 5, 0, 0, Map.of("FETCH_FAILED", 5));
        var complete = stat(TODAY, D, "COMPLETE", 40, 26, 14, Map.of());
        var found = DataAnomalyRules.externalCloseCrosscheck(List.of(aborted, complete), new DataAnomalyRules.CrosscheckDue(TODAY, 0, false), true);
        assertThat(found).extracting(DataAnomalyRules.Anomaly::key).contains("external-close-mismatch-wide");
    }

    @Test
    @DisplayName("A2 — 거래일 10:30 이후 오늘 실행이 없으면 그날 바로 경고한다")
    void missedTodayWarnsSameDay() {
        var yesterday = stat(D, D1, "COMPLETE", 40, 40, 0, Map.of());
        var found = DataAnomalyRules.externalCloseCrosscheck(List.of(yesterday), new DataAnomalyRules.CrosscheckDue(TODAY, 1, false), true);
        assertThat(found).extracting(DataAnomalyRules.Anomaly::key).contains("external-crosscheck-stale");
    }
}
