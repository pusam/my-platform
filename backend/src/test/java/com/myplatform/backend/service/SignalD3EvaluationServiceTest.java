package com.myplatform.backend.service;

import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교정 평가 배치의 순수 부분 — 멱등(같은 결과는 쓰지 않는다)·종목 묶기·구값/교정값 비교표.
 * KIS·DB 가 필요한 흐름은 단위테스트 밖(운영 로그·비교표 엔드포인트로 확인).
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
        var same = ok("10300.00", "3.00", "2.00", true);
        assertThat(SignalD3EvaluationService.needsWrite(r, same)).isFalse();
    }

    @Test
    @DisplayName("봉이 채워져 MISSING_BARS → OK 로 바뀌면 쓴다")
    void statusChangeIsWritten() {
        SignalOutcome r = row("BUY", END, "005930");
        SignalD3EvaluationService.apply(r, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음"), LocalDateTime.now());

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

    // ==================== 종목 묶기 ====================

    @Test
    @DisplayName("같은 종목의 여러 창은 한 번의 봉 수집으로 묶이고, 오래된 시그널이 앞선다")
    void groupsByStockOldestFirst() {
        var w = List.of(END.minusDays(2), END.minusDays(1), END);
        var a1 = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 3), "005930"), w);
        var b = new SignalD3EvaluationService.Due(row("BUY", LocalDate.of(2026, 9, 1), "000660"), w);
        var a2 = new SignalD3EvaluationService.Due(row("CONTROL_RANDOM", LocalDate.of(2026, 9, 2), "005930"), w);

        var grouped = SignalD3EvaluationService.groupByStock(List.of(a1, b, a2));

        assertThat(grouped.keySet()).containsExactly("000660", "005930");   // 9/1 이 먼저
        assertThat(grouped.get("005930")).hasSize(2);
    }

    // ==================== 비교표 ====================

    @Test
    @DisplayName("비교표: 둘 다 있는 행만 비교하고, hit 일치·불일치와 평균 차이를 분해한다")
    void compareDecomposesAgreement() {
        SignalOutcome both1 = row("BUY", END, "A");   // 구 hit, 교정 miss
        both1.setEvaluatedAt(LocalDateTime.now()); both1.setPctChange3d(new BigDecimal("4.0")); both1.setHit(true);
        SignalD3EvaluationService.apply(both1, ok("10100", "1.0000", "-1.0000", false), LocalDateTime.now());

        SignalOutcome both2 = row("BUY", END, "B");   // 둘 다 hit
        both2.setEvaluatedAt(LocalDateTime.now()); both2.setPctChange3d(new BigDecimal("3.0")); both2.setHit(true);
        SignalD3EvaluationService.apply(both2, ok("10300", "3.0000", "2.0000", true), LocalDateTime.now());

        SignalOutcome oldOnly = row("BUY", END, "C");  // 교정은 봉 없음
        oldOnly.setEvaluatedAt(LocalDateTime.now()); oldOnly.setPctChange3d(new BigDecimal("-2.0")); oldOnly.setHit(false);
        SignalD3EvaluationService.apply(oldOnly, SignalD3Evaluator.Result.of(
                SignalD3Evaluator.Status.MISSING_BARS, END, "봉 없음"), LocalDateTime.now());

        SignalOutcome untouched = row("CONTROL_RANDOM", END, "D");   // 아무것도 없음

        var report = SignalD3EvaluationService.compare(List.of(both1, both2, oldOnly, untouched), LocalDate.of(2026, 6, 25));

        var buy = report.byType().get("BUY");
        assertThat(buy.rows()).isEqualTo(3);
        assertThat(buy.oldEvaluated()).isEqualTo(3);
        assertThat(buy.bothEvaluated()).isEqualTo(2);
        assertThat(buy.hitAgree()).isEqualTo(1);
        assertThat(buy.hitOldOnly()).isEqualTo(1);
        assertThat(buy.hitNewOnly()).isZero();
        assertThat(buy.d3ByStatus()).containsEntry("OK", 2).containsEntry("MISSING_BARS", 1);
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
