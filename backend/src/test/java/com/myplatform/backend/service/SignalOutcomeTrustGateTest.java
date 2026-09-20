package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.TrustGateRules;
import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "믿고 사도 되나" 게이트(⑦)는 <b>V59 교정값</b>({@code d3_*})을 읽는다 — 2026-09-21 전환.
 *
 * <p>2026-09-17 까지는 기존 3일 평가({@code pct_change_3d}/{@code mae_pct_3d} — 배치 실행 시점 현재가,
 * 창 상한 없음)를 읽었다. 구값은 컬럼에 그대로 남아 있으므로, 이 테스트는 <b>구값과 교정값을 일부러
 * 다르게</b> 넣고 게이트가 어느 쪽을 읽는지를 고정한다. 교정 평가가 OK 가 아닌 행(봉 결측·지수 없음 등)은
 * 구값이 있어도 표본에서 빠지고, 반대로 구 평가가 없어도 교정 평가가 OK 면 표본이다.
 */
class SignalOutcomeTrustGateTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);
    private static final String CONTROL = ControlGroupService.CONTROL_SIGNAL_TYPE;

    /** 구값과 교정값을 따로 넣는다 — 둘이 다를 때 게이트가 어느 쪽을 읽는지가 이 테스트의 전부다. */
    private static SignalOutcome row(String type, String code, LocalDate date,
                                     String legacyPct, String legacyMae,
                                     String d3Status, String d3Pct, String d3Mae) {
        SignalOutcome s = SignalOutcome.builder()
                .signalType(type).stockCode(code).stockName(code).signalDate(date)
                .priceAtSignal(new BigDecimal("10000")).signalScore(60)
                .pctChange3d(legacyPct == null ? null : new BigDecimal(legacyPct))
                .maePct3d(legacyMae == null ? null : new BigDecimal(legacyMae))
                .evaluatedAt(legacyPct == null ? null : date.plusDays(3).atTime(19, 30))
                .d3Status(d3Status)
                .d3PctChange(d3Pct == null ? null : new BigDecimal(d3Pct))
                .d3MaePct(d3Mae == null ? null : new BigDecimal(d3Mae))
                .d3EvaluatedAt(d3Status == null ? null : date.plusDays(3).atTime(19, 45))
                .build();
        s.setCreatedAt(date.atTime(11, 30));
        return s;
    }

    private static SignalOutcome buyOk(String code, LocalDate date, String d3Pct, String d3Mae) {
        // 구값은 항상 "좋아 보이게"(+5 / -1) — 게이트가 구값을 읽으면 결과가 뒤집힌다.
        return row("BUY", code, date, "5.00", "-1.00", "OK", d3Pct, d3Mae);
    }

    private static SignalOutcome controlOk(String code, LocalDate date, String d3Pct) {
        return row(CONTROL, code, date, "1.00", "-1.00", "OK", d3Pct, "-2.00");
    }

    @Nested
    @DisplayName("구값이 아니라 교정값을 읽는다")
    class ReadsCorrected {

        @Test
        @DisplayName("수익·우위·낙폭·최악 전부 d3_* 에서 — 구값(+5%)이면 비용차감 +4.82 인데 교정값이면 -3.18")
        void correctedValuesDriveTheVerdict() {
            TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of(
                    buyOk("005930", D1, "-2.00", "-4.00"),
                    buyOk("000660", D2, "-4.00", "-6.00"),
                    controlOk("C1", D1, "-1.00"),
                    controlOk("C2", D2, "-1.00")));

            assertThat(v.rows()).isEqualTo(2);
            assertThat(v.distinctDays()).isEqualTo(2);
            assertThat(v.controlRows()).isEqualTo(2);
            // mean(-2, -4) - 0.18
            assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-3.18");
            // mean((-2)-(-1), (-4)-(-1))
            assertThat(v.edgeVsControl()).isEqualByComparingTo("-2.00");
            assertThat(v.shape().avgMaePct()).isEqualByComparingTo("-5.00");
            assertThat(v.shape().worst()).isEqualByComparingTo("-4.00");
            assertThat(v.shape().avgLoss()).isEqualByComparingTo("-3.00");
            assertThat(v.shape().avgWin()).isNull();   // 교정값엔 이익 건이 없다(구값이면 둘 다 +5)
        }

        @Test
        @DisplayName("교정 평가가 OK 가 아닌 행은 구값이 있어도 표본에서 빠진다 — 봉 결측을 구값으로 메우지 않는다")
        void nonOkRowsAreExcludedEvenWithLegacyValues() {
            TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of(
                    buyOk("005930", D1, "-2.00", "-4.00"),
                    row("BUY", "000660", D2, "5.00", "-1.00", "MISSING_BARS", null, null),
                    controlOk("C1", D1, "-1.00"),
                    controlOk("C2", D2, "-1.00")));

            assertThat(v.rows()).isEqualTo(1);
            assertThat(v.distinctDays()).isEqualTo(1);
            assertThat(v.excludedDays()).isZero();     // 9/2 는 시그널 표본 자체가 없다(짝 없는 날이 아니라)
            assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.18");
        }

        @Test
        @DisplayName("구 평가가 없어도(evaluated_at NULL) 교정 평가가 OK 면 표본이다 — 15일 give-up 뒤 백필된 행")
        void correctedOnlyRowsCount() {
            TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of(
                    row("BUY", "005930", D1, null, null, "OK", "-2.00", "-4.00"),
                    row(CONTROL, "C1", D1, null, null, "OK", "-1.00", "-2.00")));

            assertThat(v.rows()).isEqualTo(1);
            assertThat(v.distinctDays()).isEqualTo(1);
            assertThat(v.controlRows()).isEqualTo(1);
            assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.18");
        }

        @Test
        @DisplayName("대조군도 같은 규칙 — 교정 미평가 대조군 행은 짝이 되지 못하고 그날은 excludedDays 로 드러난다")
        void controlRowsFollowTheSameRule() {
            TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of(
                    buyOk("005930", D1, "-2.00", "-4.00"),
                    buyOk("000660", D2, "-4.00", "-6.00"),
                    controlOk("C1", D1, "-1.00"),
                    row(CONTROL, "C2", D2, "1.00", "-1.00", "NO_INDEX", null, null)));

            assertThat(v.excludedDays()).isEqualTo(1);
            assertThat(v.rows()).isEqualTo(1);
            assertThat(v.controlRows()).isEqualTo(1);
            assertThat(v.distinctDays()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("측정 위생은 그대로 재사용 — 같은 종목·같은 날 승격 2행은 최초 기록 1건(교정값)만")
    void dedupStillAppliesToCorrectedRows() {
        SignalOutcome first = buyOk("005930", D1, "-2.00", "-4.00");
        SignalOutcome upgraded = row("STRONG_BUY", "005930", D1, "5.00", "-1.00", "OK", "9.00", "-0.50");
        upgraded.setCreatedAt(D1.atTime(20, 5));

        TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of(
                first, upgraded, controlOk("C1", D1, "0.00")));

        assertThat(v.rows()).isEqualTo(1);
        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.18");
    }

    @Test
    @DisplayName("빈 입력은 근거 없음(0 행) — 예외가 아니다")
    void emptyInputIsCollecting() {
        TrustGateRules.Verdict v = SignalOutcomeService.aggregateTrustGate(List.of());

        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.rows()).isZero();
        assertThat(v.costAdjustedReturn()).isNull();
    }
}
