package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 교정 평가 배치 — 멱등(저장 필드 전부 비교)·응답 봉으로만 평가·수집 실패 기록·비교표.
 * 2026-09-17 코덱스 리뷰 1차 ①⑤·2차 ①② 의 재현 테스트가 여기 있다(재시도 조건·순서는 DB 쿼리
 * 테스트 {@code SignalOutcomeRepositoryD3Test} 로 옮겼다).
 */
class SignalD3EvaluationServiceTest {

    private static final LocalDate SIGNAL = LocalDate.of(2026, 9, 7);            // 월
    private static final List<LocalDate> WINDOW = List.of(
            LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10));
    private static final LocalDate END = WINDOW.get(2);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T10:45:00Z"), ZoneId.of("Asia/Seoul"));

    private static SignalD3Evaluator.Result ok(String close, String pct, String alpha, boolean hit) {
        return new SignalD3Evaluator.Result(SignalD3Evaluator.Status.OK, END, new BigDecimal(close),
                new BigDecimal(pct), new BigDecimal("3030"), new BigDecimal("1.0000"), new BigDecimal(alpha),
                new BigDecimal("6.0000"), new BigDecimal("-3.0000"), hit, null);
    }

    private static SignalOutcome row(String type, LocalDate date, String code) {
        return SignalOutcome.builder().signalType(type).signalDate(date).stockCode(code)
                .priceAtSignal(new BigDecimal("10000")).bmPriceAtSignal(new BigDecimal("3000")).build();
    }

    private static SignalD3Evaluator.Result missing() {
        return SignalD3Evaluator.Result.of(SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음");
    }

    private static KoreaInvestmentService.OhlcvData ohlcv(LocalDate d, String close) {
        var o = new KoreaInvestmentService.OhlcvData(new BigDecimal(close), new BigDecimal(close).add(BigDecimal.TEN),
                new BigDecimal(close).subtract(BigDecimal.TEN), new BigDecimal(close), new BigDecimal("1000"));
        o.setTradeDate(d);
        return o;
    }

    // ==================== 멱등 ====================

    @Test
    @DisplayName("아직 시도 안 한 행(status NULL)은 쓴다")
    void unattemptedRowIsWritten() {
        assertThat(SignalD3EvaluationService.needsWrite(row("BUY", END, "005930"), ok("10300", "3.0000", "2.0000", true))).isTrue();
    }

    @Test
    @DisplayName("백필을 다시 돌려도 같은 결과면 쓰지 않는다 — 불필요한 덮어쓰기 없음")
    void identicalResultIsNotRewritten() {
        SignalOutcome r = row("BUY", END, "005930");
        SignalD3EvaluationService.apply(r, ok("10300", "3.0000", "2.0000", true), LocalDateTime.now());

        assertThat(SignalD3EvaluationService.needsWrite(r, ok("10300.00", "3.00", "2.00", true))).isFalse();
    }

    @Test
    @DisplayName("종가는 같고 고가·저가만 교정되면 쓴다 — 저장 필드 전부를 비교한다(1차 ⑤)")
    void mfeMaeOnlyChangeIsWritten() {
        SignalOutcome r = row("BUY", END, "005930");
        SignalD3EvaluationService.apply(r, ok("10300", "3.0000", "2.0000", true), LocalDateTime.now());

        var mfeChanged = new SignalD3Evaluator.Result(SignalD3Evaluator.Status.OK, END, new BigDecimal("10300"),
                new BigDecimal("3.0000"), new BigDecimal("3030"), new BigDecimal("1.0000"), new BigDecimal("2.0000"),
                new BigDecimal("7.5000"), new BigDecimal("-3.0000"), true, null);
        assertThat(SignalD3EvaluationService.needsWrite(r, mfeChanged)).isTrue();

        var bmChanged = new SignalD3Evaluator.Result(SignalD3Evaluator.Status.OK, END, new BigDecimal("10300"),
                new BigDecimal("3.0000"), new BigDecimal("3040"), new BigDecimal("1.3333"), new BigDecimal("1.6667"),
                new BigDecimal("6.0000"), new BigDecimal("-3.0000"), true, null);
        assertThat(SignalD3EvaluationService.needsWrite(r, bmChanged)).isTrue();
    }

    @Test
    @DisplayName("봉이 채워져 MISSING_BARS → OK 로 바뀌면 쓴다")
    void statusChangeIsWritten() {
        SignalOutcome r = row("BUY", END, "005930");
        SignalD3EvaluationService.apply(r, missing(), LocalDateTime.now());
        assertThat(SignalD3EvaluationService.needsWrite(r, ok("10300", "3.0000", "2.0000", true))).isTrue();
    }

    @Test
    @DisplayName("사유 문구만 다른 재시도는 쓰지 않는다")
    void noteOnlyDifferenceIsNotRewritten() {
        SignalOutcome r = row("BUY", END, "005930");
        SignalD3EvaluationService.apply(r, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음: [2026-09-09]"), LocalDateTime.now());
        assertThat(SignalD3EvaluationService.needsWrite(r, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음: [2026-09-09] (재시도)"))).isFalse();
    }

    @Test
    @DisplayName("apply 는 구값(price_after_3d·hit)을 건드리지 않는다")
    void applyLeavesLegacyColumnsUntouched() {
        SignalOutcome r = row("BUY", END, "005930");
        r.setPriceAfter3d(new BigDecimal("9000"));
        r.setPctChange3d(new BigDecimal("-10.0000"));
        r.setHit(false);
        r.setEvaluatedAt(LocalDateTime.of(2026, 9, 12, 19, 30));

        SignalD3EvaluationService.apply(r, ok("10300", "3.0000", "2.0000", true), LocalDateTime.now());

        assertThat(r.getPriceAfter3d()).isEqualByComparingTo("9000");
        assertThat(r.getPctChange3d()).isEqualByComparingTo("-10.0000");
        assertThat(r.getHit()).isFalse();
        assertThat(r.getEvaluatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 12, 19, 30));
        assertThat(r.getD3Hit()).isTrue();
        assertThat(r.getD3Status()).isEqualTo("OK");
    }

    @Test
    @DisplayName("같은 종목의 여러 창은 한 번의 봉 수집으로 묶이고, DB 가 준 순서를 보존한다")
    void groupsByStockPreservingOrder() {
        var w = WINDOW;
        var b = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 1), "000660"), w);
        var a1 = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 3), "005930"), w);
        var a2 = new SignalD3EvaluationService.Due(row("CONTROL_RANDOM", LocalDate.of(2026, 9, 2), "005930"), w);

        var grouped = SignalD3EvaluationService.groupByStock(List.of(b, a1, a2));

        assertThat(grouped.keySet()).containsExactly("000660", "005930");
        assertThat(grouped.get("005930")).hasSize(2);
    }

    // ==================== 배치 흐름(Mockito) ====================

    /** 공통 조립 — 한 종목·한 대기 행, DB 엔 옛 봉이 창 전체에 있다(섞이면 안 되는 미끼). */
    private record Rig(SignalOutcomeRepository repo, StockPriceHistoryRepository hist,
                       StockAnalysisService analysis, KoreaInvestmentService kis,
                       SignalOutcome pending, SignalD3EvaluationService svc) {}

    @SuppressWarnings("unchecked")
    private static Rig rig() {
        var repo = mock(SignalOutcomeRepository.class);
        var hist = mock(StockPriceHistoryRepository.class);
        var analysis = mock(StockAnalysisService.class);
        var kis = mock(KoreaInvestmentService.class);
        ObjectProvider<StockAnalysisService> analysisP = mock(ObjectProvider.class);
        ObjectProvider<KoreaInvestmentService> kisP = mock(ObjectProvider.class);
        ObjectProvider<StockStatusService> statusP = mock(ObjectProvider.class);
        when(analysisP.getIfAvailable()).thenReturn(analysis);
        when(kisP.getIfAvailable()).thenReturn(kis);
        when(statusP.getIfAvailable()).thenReturn(null);
        when(kis.isConfigured()).thenReturn(true);
        when(kis.getIndexDailyOhlcv(any(), anyInt(), any())).thenReturn(List.of(
                new KoreaInvestmentService.IndexOhlcvData("20260910", null, null, null, new BigDecimal("3030"))));

        SignalOutcome pending = row("BUY", SIGNAL, "005930");
        when(repo.findD3Pending(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(pending));

        // DB 미끼 — 창 전체에 옛 봉이 있다. 응답이 부족할 때 이걸로 채우면 실패다.
        var bait = WINDOW.stream().map(d -> {
            StockPriceHistory h = new StockPriceHistory();
            h.setTradeDate(d); h.setClosePrice(new BigDecimal("9000")); h.setHighPrice(new BigDecimal("9100"));
            h.setLowPrice(new BigDecimal("8900")); h.setVolume(new BigDecimal("1000"));
            return h;
        }).toList();
        when(hist.findByStockCodeAndDateRange(any(), any(), any())).thenReturn(bait);

        var svc = new SignalD3EvaluationService(repo, hist, new MarketCalendarService(), analysisP, kisP, statusP, CLOCK);
        return new Rig(repo, hist, analysis, kis, pending, svc);
    }

    @Test
    @DisplayName("빈 응답이면 FETCH_FAILED + 사유 + 시각을 기록하고 평가하지 않는다 — DB 옛 봉으로 이어가지 않는다(2차 ①)")
    void emptyResponseIsRecordedAsFetchFailed() {
        Rig g = rig();
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, List.of(), 0));

        var report = g.svc().run(false, 120, false);

        assertThat(report.fetchFailed()).isEqualTo(1);
        assertThat(report.fetchFailedRows()).isEqualTo(1);
        assertThat(report.evaluatedByStatus()).isEmpty();
        assertThat(g.pending().getD3Status()).isEqualTo("FETCH_FAILED");
        assertThat(g.pending().getD3Note()).contains("빈 응답");
        assertThat(g.pending().getD3EvaluatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(g.pending().getD3PctChange()).isNull();
        verify(g.repo()).save(g.pending());
        // 수집 범위는 오늘 기준 60봉이 아니라 그 창을 덮는 과거 구간이다
        verify(g.analysis()).collectPriceHistoryRange(eq("005930"),
                eq(WINDOW.get(0).minusDays(SignalD3EvaluationService.FETCH_LEAD_DAYS)), eq(END));
    }

    @Test
    @DisplayName("저장 실패(DataAccessException)는 호출부로 올라와 FETCH_FAILED 로 기록된다 — 삼키지 않는다(2차 ②)")
    void persistFailurePropagatesAndIsRecorded() {
        Rig g = rig();
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        var report = g.svc().run(false, 120, false);

        assertThat(report.fetchFailed()).isEqualTo(1);
        assertThat(g.pending().getD3Status()).isEqualTo("FETCH_FAILED");
        assertThat(g.pending().getD3Note()).contains("DataAccessResourceFailureException").contains("connection lost");
        assertThat(report.evaluatedByStatus()).isEmpty();
    }

    @Test
    @DisplayName("동시 수집 중이면 받은 것이 없으므로 FETCH_FAILED — 다음 날 다시")
    void busyIsRecordedAsFetchFailed() {
        Rig g = rig();
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(StockAnalysisService.CollectResult.busy());

        g.svc().run(false, 120, false);

        assertThat(g.pending().getD3Status()).isEqualTo("FETCH_FAILED");
        assertThat(g.pending().getD3Note()).contains("수집 중");
    }

    @Test
    @DisplayName("일부 응답(D+3 없음)은 개수가 많아도 MISSING_BARS — DB 의 D+3 봉으로 채워 성공 처리하지 않는다(2차 ②)")
    void partialResponseIsMissingBarsNotMixedWithDb() {
        Rig g = rig();
        // 창 앞쪽 여유까지 10봉이 왔지만 정작 D+3(9/10)은 없다
        var bars = new java.util.ArrayList<KoreaInvestmentService.OhlcvData>();
        for (int i = 1; i <= 10; i++) bars.add(ohlcv(WINDOW.get(0).minusDays(i), "10000"));
        bars.add(ohlcv(WINDOW.get(0), "10100"));
        bars.add(ohlcv(WINDOW.get(1), "10200"));
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, bars, bars.size()));

        var report = g.svc().run(false, 120, false);

        assertThat(report.fetched()).isEqualTo(1);
        assertThat(report.evaluatedByStatus()).containsEntry("MISSING_BARS", 1);
        assertThat(g.pending().getD3Status()).isEqualTo("MISSING_BARS");
        assertThat(g.pending().getD3Note()).contains("2026-09-10");
        assertThat(g.pending().getD3PctChange()).isNull();
    }

    @Test
    @DisplayName("응답에 창 세 봉이 다 있으면 응답 값으로 평가한다 — DB 미끼(종가 9,000)가 아니라 응답(10,300)")
    void fullResponseIsEvaluatedFromResponseBars() {
        Rig g = rig();
        var bars = List.of(
                ohlcv(SIGNAL, "10050"),            // D0 — 단위 판정 앵커
                ohlcv(WINDOW.get(0), "10100"),
                ohlcv(WINDOW.get(1), "10500"),
                ohlcv(WINDOW.get(2), "10300"));
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, bars, 4));

        var report = g.svc().run(false, 120, false);

        assertThat(report.evaluatedByStatus()).containsEntry("OK", 1);
        assertThat(g.pending().getD3Status()).isEqualTo("OK");
        assertThat(g.pending().getD3Close()).isEqualByComparingTo("10300");
        assertThat(g.pending().getD3PctChange()).isEqualByComparingTo("3.0000");
        assertThat(g.pending().getD3BmClose()).isEqualByComparingTo("3030");
        assertThat(g.pending().getD3Alpha()).isEqualByComparingTo("2.0000");
        assertThat(g.pending().getD3Hit()).isTrue();
    }

    @Test
    @DisplayName("force 재평가 중 수집이 실패해도 이미 OK 인 행은 덮지 않는다")
    void fetchFailureDoesNotClobberOkRowsUnderForce() {
        Rig g = rig();
        SignalD3EvaluationService.apply(g.pending(), ok("10300", "3.0000", "2.0000", true), LocalDateTime.of(2026, 9, 16, 19, 45));
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, List.of(), 0));

        var report = g.svc().run(false, 120, true);

        assertThat(report.fetchFailed()).isEqualTo(1);
        assertThat(report.fetchFailedRows()).isZero();
        assertThat(g.pending().getD3Status()).isEqualTo("OK");
        verify(g.repo(), never()).save(any());
    }

    @Test
    @DisplayName("사전 점검(dryRun)은 달력이 정한 D+1·D+2·D+3 봉의 DB 존재로 완전성을 세고 KIS 를 부르지 않는다")
    void dryRunCountsExactTradingDayCoverageWithoutFetching() {
        Rig g = rig();
        // DB 엔 9/8·9/9 만 있다 → missingBars
        StockPriceHistory b1 = new StockPriceHistory(); b1.setTradeDate(WINDOW.get(0)); b1.setClosePrice(BigDecimal.TEN);
        StockPriceHistory b2 = new StockPriceHistory(); b2.setTradeDate(WINDOW.get(1)); b2.setClosePrice(BigDecimal.TEN);
        when(g.hist().findByStockCodeAndDateRange(any(), any(), any())).thenReturn(List.of(b1, b2));

        var report = g.svc().run(true, 120, false);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.coverageByType().get("BUY")).containsEntry("missingBars", 1);
        verify(g.analysis(), never()).collectPriceHistoryRange(any(), any(), any());
        verify(g.repo(), never()).save(any());
    }

    @Test
    @DisplayName("대기 조회는 재시도 조건·정렬을 DB 에 맡긴다 — 서비스는 상한·간격 파라미터를 넘길 뿐")
    void pendingQueryReceivesRetryParameters() {
        Rig g = rig();
        when(g.analysis().collectPriceHistoryRange(any(), any(), any()))
                .thenReturn(new StockAnalysisService.CollectResult(false, List.of(), 0));

        g.svc().run(false, 120, false);

        var retryBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        var fetchRetryBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(g.repo()).findD3Pending(eq(SignalOutcomeService.PHASE38_CUTOFF),
                eq(LocalDate.now(CLOCK).minusDays(SignalD3EvaluationService.MIN_CALENDAR_DAYS_TO_DUE)),
                eq(SignalD3EvaluationService.RETRYABLE), retryBefore.capture(), eq("FETCH_FAILED"), fetchRetryBefore.capture(), any());
        assertThat(retryBefore.getValue()).isEqualTo(LocalDateTime.now(CLOCK).minusDays(7));
        assertThat(fetchRetryBefore.getValue()).isEqualTo(LocalDateTime.now(CLOCK).minusDays(1));
    }

    // ==================== 비교표 ====================

    @Test
    @DisplayName("비교표: 교정 OK 인 행만 비교하고(NO_INDEX·FETCH_FAILED 제외), hit 일치·불일치와 평균 차이를 분해한다")
    void compareDecomposesAgreementAndExcludesUnevaluated() {
        SignalOutcome both1 = row("BUY", END, "A");   // 구 hit, 교정 miss
        both1.setEvaluatedAt(LocalDateTime.now()); both1.setPctChange3d(new BigDecimal("4.0")); both1.setHit(true);
        SignalD3EvaluationService.apply(both1, ok("10100", "1.0000", "-1.0000", false), LocalDateTime.now());

        SignalOutcome both2 = row("BUY", END, "B");   // 둘 다 hit
        both2.setEvaluatedAt(LocalDateTime.now()); both2.setPctChange3d(new BigDecimal("3.0")); both2.setHit(true);
        SignalD3EvaluationService.apply(both2, ok("10300", "3.0000", "2.0000", true), LocalDateTime.now());

        SignalOutcome oldOnly = row("BUY", END, "C");  // 교정은 봉 없음
        oldOnly.setEvaluatedAt(LocalDateTime.now()); oldOnly.setPctChange3d(new BigDecimal("-2.0")); oldOnly.setHit(false);
        SignalD3EvaluationService.apply(oldOnly, missing(), LocalDateTime.now());

        SignalOutcome noIndex = row("BUY", END, "E");   // 지수 없음 = 미평가
        noIndex.setEvaluatedAt(LocalDateTime.now()); noIndex.setPctChange3d(new BigDecimal("9.0")); noIndex.setHit(true);
        SignalD3EvaluationService.apply(noIndex, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.NO_INDEX, END, "지수 없음"), LocalDateTime.now());

        SignalOutcome fetchFailed = row("BUY", END, "F");
        fetchFailed.setEvaluatedAt(LocalDateTime.now()); fetchFailed.setPctChange3d(new BigDecimal("1.0")); fetchFailed.setHit(false);
        SignalD3EvaluationService.apply(fetchFailed, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.FETCH_FAILED, END, "봉 수집 실패: 빈 응답"), LocalDateTime.now());

        SignalOutcome untouched = row("CONTROL_RANDOM", END, "D");   // 아무것도 없음

        var report = SignalD3EvaluationService.compare(
                List.of(both1, both2, oldOnly, noIndex, fetchFailed, untouched), LocalDate.of(2026, 6, 25));

        var buy = report.byType().get("BUY");
        assertThat(buy.rows()).isEqualTo(5);
        assertThat(buy.oldEvaluated()).isEqualTo(5);
        assertThat(buy.bothEvaluated()).isEqualTo(2);
        assertThat(buy.hitAgree()).isEqualTo(1);
        assertThat(buy.hitOldOnly()).isEqualTo(1);
        assertThat(buy.hitNewOnly()).isZero();
        assertThat(buy.d3ByStatus()).containsEntry("OK", 2).containsEntry("MISSING_BARS", 1)
                .containsEntry("NO_INDEX", 1).containsEntry("FETCH_FAILED", 1);
        assertThat(buy.oldAvgPct()).isEqualByComparingTo("3.50");
        assertThat(buy.newAvgPct()).isEqualByComparingTo("2.00");
        assertThat(buy.avgAbsPctDiff()).isEqualByComparingTo("1.50");

        var ctl = report.byType().get("CONTROL_RANDOM");
        assertThat(ctl.d3NotAttempted()).isEqualTo(1);
        assertThat(ctl.bothEvaluated()).isZero();
        assertThat(ctl.oldAvgPct()).isNull();
        assertThat(report.caveat()).contains("게이트 전환");
    }
}
