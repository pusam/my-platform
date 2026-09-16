package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import com.myplatform.backend.entity.StockPriceHistory;
import com.myplatform.backend.repository.SignalOutcomeRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 교정 평가 배치 — 멱등(저장 필드 전부 비교)·재시도 간격/순환·봉을 못 받으면 평가하지 않음·비교표.
 * 2026-09-17 코덱스 리뷰 ①④⑤의 재현 테스트가 여기 있다.
 */
class SignalD3EvaluationServiceTest {

    private static final LocalDate END = LocalDate.of(2026, 9, 10);

    private static SignalD3Evaluator.Result ok(String close, String pct, String alpha, boolean hit) {
        return new SignalD3Evaluator.Result(SignalD3Evaluator.Status.OK, END, new BigDecimal(close),
                new BigDecimal(pct), new BigDecimal("3030"), new BigDecimal("1.0000"), new BigDecimal(alpha),
                new BigDecimal("6.0000"), new BigDecimal("-3.0000"), hit, null);
    }

    private static SignalOutcome row(String type, LocalDate date, String code) {
        return SignalOutcome.builder().signalType(type).signalDate(date).stockCode(code)
                .priceAtSignal(new BigDecimal("10000")).build();
    }

    private static SignalD3Evaluator.Result missing() {
        return SignalD3Evaluator.Result.of(SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음");
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

        // scale 이 달라도 같은 수면 같다(10300 vs 10300.00)
        assertThat(SignalD3EvaluationService.needsWrite(r, ok("10300.00", "3.00", "2.00", true))).isFalse();
    }

    @Test
    @DisplayName("종가는 같고 고가·저가만 교정되면 쓴다 — 저장 필드 전부를 비교한다(코덱스 리뷰 ⑤)")
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

    // ==================== 재시도 간격·순환(코덱스 리뷰 ④) ====================

    @Test
    @DisplayName("한 번 시도한 재시도 행은 7일 동안 미룬다 — 영구 결측 종목이 매 실행 상한을 먹지 않게")
    void retryIsDeferredForInterval() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 19, 45);
        SignalOutcome never = row("BUY", END, "A");
        SignalOutcome recent = row("BUY", END, "B");
        SignalD3EvaluationService.apply(recent, missing(), now.minusDays(2));
        SignalOutcome stale = row("BUY", END, "C");
        SignalD3EvaluationService.apply(stale, missing(), now.minusDays(8));

        assertThat(SignalD3EvaluationService.eligibleForRetry(never, now)).isTrue();
        assertThat(SignalD3EvaluationService.eligibleForRetry(recent, now)).isFalse();
        assertThat(SignalD3EvaluationService.eligibleForRetry(stale, now)).isTrue();
    }

    @Test
    @DisplayName("백필 순서: 시도 안 한 행 → 가장 오래전 시도 → 오래된 시그널 — 오래된 결측이 앞줄을 독점하지 않는다")
    void backfillOrderRotates() {
        var w = List.of(END.minusDays(2), END.minusDays(1), END);
        LocalDateTime now = LocalDateTime.of(2026, 9, 17, 19, 45);
        SignalOutcome oldStuck = row("BUY", LocalDate.of(2026, 7, 1), "OLD");      // 오래됐고 8일 전 시도
        SignalD3EvaluationService.apply(oldStuck, missing(), now.minusDays(8));
        SignalOutcome older = row("BUY", LocalDate.of(2026, 7, 2), "OLDER");       // 더 오래전 시도
        SignalD3EvaluationService.apply(older, missing(), now.minusDays(20));
        SignalOutcome fresh = row("BUY", LocalDate.of(2026, 9, 10), "NEW");        // 시도한 적 없음

        var ordered = SignalD3EvaluationService.orderForBackfill(List.of(
                new SignalD3EvaluationService.Due(oldStuck, w),
                new SignalD3EvaluationService.Due(older, w),
                new SignalD3EvaluationService.Due(fresh, w)));

        assertThat(ordered).extracting(d -> d.row().getStockCode()).containsExactly("NEW", "OLDER", "OLD");
    }

    @Test
    @DisplayName("같은 종목의 여러 창은 한 번의 봉 수집으로 묶이고, 정렬 순서를 보존한다")
    void groupsByStockPreservingOrder() {
        var w = List.of(END.minusDays(2), END.minusDays(1), END);
        var a1 = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 3), "005930"), w);
        var b = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 1), "000660"), w);
        var a2 = new SignalD3EvaluationService.Due(row("CONTROL_RANDOM", LocalDate.of(2026, 9, 2), "005930"), w);

        var grouped = SignalD3EvaluationService.groupByStock(
                SignalD3EvaluationService.orderForBackfill(List.of(a1, b, a2)));

        assertThat(grouped.keySet()).containsExactly("000660", "005930");   // 9/1 이 먼저
        assertThat(grouped.get("005930")).hasSize(2);
    }

    // ==================== 봉을 못 받으면 평가하지 않는다(코덱스 리뷰 ①) ====================

    @Test
    @DisplayName("봉 수집이 빈 응답(0건)이면 그 종목은 평가하지 않고 fetchFailed 로 센다 — 기존 DB 봉으로 이어가지 않는다")
    @SuppressWarnings("unchecked")
    void emptyFetchMeansNoEvaluation() {
        var repo = mock(SignalOutcomeRepository.class);
        var hist = mock(StockPriceHistoryRepository.class);
        var analysis = mock(StockAnalysisService.class);
        ObjectProvider<StockAnalysisService> analysisP = mock(ObjectProvider.class);
        ObjectProvider<KoreaInvestmentService> kisP = mock(ObjectProvider.class);
        ObjectProvider<StockStatusService> statusP = mock(ObjectProvider.class);
        when(analysisP.getIfAvailable()).thenReturn(analysis);
        when(kisP.getIfAvailable()).thenReturn(null);
        when(statusP.getIfAvailable()).thenReturn(null);

        SignalOutcome pending = row("BUY", LocalDate.of(2026, 9, 7), "005930");
        when(repo.findD3Pending(any(), any(), any())).thenReturn(List.of(pending));
        // DB 엔 옛 봉이 있다 — 그래도 새로 못 받았으면 쓰면 안 된다
        StockPriceHistory stale = new StockPriceHistory();
        stale.setTradeDate(LocalDate.of(2026, 9, 8));
        stale.setClosePrice(new BigDecimal("10000"));
        when(hist.findByStockCodeAndDateRange(any(), any(), any())).thenReturn(List.of(stale));
        when(analysis.collectPriceHistoryRange(any(), any(), any())).thenReturn(0);

        Clock clock = Clock.fixed(Instant.parse("2026-09-17T10:45:00Z"), ZoneId.of("Asia/Seoul"));
        var svc = new SignalD3EvaluationService(repo, hist, new MarketCalendarService(), analysisP, kisP, statusP, clock);

        var report = svc.run(false, 120, false);

        assertThat(report.fetchFailed()).isEqualTo(1);
        assertThat(report.fetched()).isZero();
        assertThat(report.evaluatedByStatus()).isEmpty();
        verify(repo, never()).save(any());
        // 수집 범위는 오늘 기준 60봉이 아니라 그 창을 덮는 과거 구간이다(D+1 9/8 − 10일 ~ D+3 9/10)
        verify(analysis).collectPriceHistoryRange(eq("005930"),
                eq(LocalDate.of(2026, 9, 8).minusDays(SignalD3EvaluationService.FETCH_LEAD_DAYS)),
                eq(LocalDate.of(2026, 9, 10)));
    }

    @Test
    @DisplayName("사전 점검(dryRun)은 달력이 정한 D+1·D+2·D+3 봉 존재로 완전성을 세고 KIS 를 부르지 않는다")
    @SuppressWarnings("unchecked")
    void dryRunCountsExactTradingDayCoverageWithoutFetching() {
        var repo = mock(SignalOutcomeRepository.class);
        var hist = mock(StockPriceHistoryRepository.class);
        var analysis = mock(StockAnalysisService.class);
        ObjectProvider<StockAnalysisService> analysisP = mock(ObjectProvider.class);
        ObjectProvider<KoreaInvestmentService> kisP = mock(ObjectProvider.class);
        ObjectProvider<StockStatusService> statusP = mock(ObjectProvider.class);
        when(analysisP.getIfAvailable()).thenReturn(analysis);

        // 9/7(월) 시그널 → 창 9/8·9/9·9/10. DB 엔 9/8·9/9 만 있다 → missingBars
        SignalOutcome pending = row("BUY", LocalDate.of(2026, 9, 7), "005930");
        when(repo.findD3Pending(any(), any(), any())).thenReturn(List.of(pending));
        StockPriceHistory b1 = new StockPriceHistory(); b1.setTradeDate(LocalDate.of(2026, 9, 8)); b1.setClosePrice(BigDecimal.TEN);
        StockPriceHistory b2 = new StockPriceHistory(); b2.setTradeDate(LocalDate.of(2026, 9, 9)); b2.setClosePrice(BigDecimal.TEN);
        when(hist.findByStockCodeAndDateRange(any(), any(), any())).thenReturn(List.of(b1, b2));

        Clock clock = Clock.fixed(Instant.parse("2026-09-17T10:45:00Z"), ZoneId.of("Asia/Seoul"));
        var svc = new SignalD3EvaluationService(repo, hist, new MarketCalendarService(), analysisP, kisP, statusP, clock);

        var report = svc.run(true, 120, false);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.coverageByType().get("BUY")).containsEntry("missingBars", 1);
        verify(analysis, never()).collectPriceHistoryRange(any(), any(), any());
        verify(repo, never()).save(any());
    }

    // ==================== 비교표 ====================

    @Test
    @DisplayName("비교표: 교정 OK 인 행만 비교하고(NO_INDEX 제외), hit 일치·불일치와 평균 차이를 분해한다")
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

        SignalOutcome noIndex = row("BUY", END, "E");   // 지수 없음 = 미평가 → 비교에서 빠진다
        noIndex.setEvaluatedAt(LocalDateTime.now()); noIndex.setPctChange3d(new BigDecimal("9.0")); noIndex.setHit(true);
        SignalD3EvaluationService.apply(noIndex, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.NO_INDEX, END, "지수 없음"), LocalDateTime.now());

        SignalOutcome untouched = row("CONTROL_RANDOM", END, "D");   // 아무것도 없음

        var report = SignalD3EvaluationService.compare(
                List.of(both1, both2, oldOnly, noIndex, untouched), LocalDate.of(2026, 6, 25));

        var buy = report.byType().get("BUY");
        assertThat(buy.rows()).isEqualTo(4);
        assertThat(buy.oldEvaluated()).isEqualTo(4);
        assertThat(buy.bothEvaluated()).isEqualTo(2);           // NO_INDEX·MISSING_BARS 는 비교 표본이 아니다
        assertThat(buy.hitAgree()).isEqualTo(1);
        assertThat(buy.hitOldOnly()).isEqualTo(1);
        assertThat(buy.hitNewOnly()).isZero();
        assertThat(buy.d3ByStatus()).containsEntry("OK", 2).containsEntry("MISSING_BARS", 1).containsEntry("NO_INDEX", 1);
        assertThat(buy.oldAvgPct()).isEqualByComparingTo("3.50");
        assertThat(buy.newAvgPct()).isEqualByComparingTo("2.00");
        assertThat(buy.avgAbsPctDiff()).isEqualByComparingTo("1.50");

        var ctl = report.byType().get("CONTROL_RANDOM");
        assertThat(ctl.d3NotAttempted()).isEqualTo(1);
        assertThat(ctl.bothEvaluated()).isZero();
        assertThat(ctl.oldAvgPct()).isNull();   // 비교 표본 없음 = null(0 아님)
        assertThat(report.caveat()).contains("게이트 전환");
    }
}
