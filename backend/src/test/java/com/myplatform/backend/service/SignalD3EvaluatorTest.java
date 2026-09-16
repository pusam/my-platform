package com.myplatform.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "기록시점 → D+3 KRX 종가" 교정 평가 — {@link SignalD3Evaluator}.
 *
 * <p>고정하는 성질: ① 실행 시각과 무관(지연 평가 = 정상 평가) ② 창은 달력이 정하고 봉이 없어도
 * 밀리지 않는다 ③ 모르는 것은 사유로 남기고 0 이나 위장값을 쓰지 않는다(§4c).
 */
class SignalD3EvaluatorTest {

    // 2026-09-07(월) 시그널 → D+1 9/8, D+2 9/9, D+3 9/10
    private static final LocalDate D0 = LocalDate.of(2026, 9, 7);
    private static final List<LocalDate> WINDOW = List.of(
            LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10));
    private static final BigDecimal P0 = new BigDecimal("10000");
    private static final BigDecimal BM0 = new BigDecimal("3000.00");

    private static SignalD3Evaluator.Bar bar(LocalDate d, String high, String low, String close, String vol) {
        return new SignalD3Evaluator.Bar(d, new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                vol == null ? null : new BigDecimal(vol));
    }

    /** D0 포함 정상 4봉. D+3 종가 10,300(+3%), 창 최고 10,600(+6%), 최저 9,700(-3%). */
    private static Map<LocalDate, SignalD3Evaluator.Bar> normalBars() {
        Map<LocalDate, SignalD3Evaluator.Bar> m = new HashMap<>();
        m.put(D0, bar(D0, "10100", "9900", "10050", "1000"));
        m.put(WINDOW.get(0), bar(WINDOW.get(0), "10200", "9700", "10100", "1000"));
        m.put(WINDOW.get(1), bar(WINDOW.get(1), "10600", "10000", "10500", "1000"));
        m.put(WINDOW.get(2), bar(WINDOW.get(2), "10400", "10100", "10300", "1000"));
        return m;
    }

    // ==================== ① 실행 시각과 무관 ====================

    @Test
    @DisplayName("정상 평가(D+3 당일)와 열흘 늦은 평가가 같은 값을 낸다 — 배치가 밀려도 수익률이 안 바뀐다")
    void delayedEvaluationEqualsOnTimeEvaluation() {
        var onTime = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), new BigDecimal("3030"), null);
        var late = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(10), WINDOW, P0, BM0, normalBars(), new BigDecimal("3030"), null);

        assertThat(onTime.status()).isEqualTo(SignalD3Evaluator.Status.OK);
        assertThat(late).isEqualTo(onTime);
    }

    @Test
    @DisplayName("D+3 이 아직 안 지났으면 NOT_DUE — 저장하지 않는다")
    void notDueBeforeD3() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(1), WINDOW, P0, BM0, normalBars(), null, null);
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NOT_DUE);
        assertThat(r.pctChange()).isNull();
    }

    // ==================== ② 창은 달력이 정한다 ====================

    @Test
    @DisplayName("D+2 봉이 없으면 MISSING_BARS — D+4 봉이 있어도 그걸로 채워 평가 기간을 늘리지 않는다")
    void missingBarDoesNotShiftTheWindow() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.remove(WINDOW.get(1));
        bars.put(WINDOW.get(2).plusDays(1), bar(WINDOW.get(2).plusDays(1), "12000", "11000", "11500", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(5), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.MISSING_BARS);
        assertThat(r.endDate()).isEqualTo(WINDOW.get(2));   // 종료일은 그대로
        assertThat(r.note()).contains("2026-09-09");
        assertThat(r.pctChange()).isNull();                   // 부분 값 없음
    }

    @Test
    @DisplayName("창 밖(D+4~D+7) 봉은 최고·최저에 섞이지 않는다 — 옛 구현의 '최근 7봉' 오염 차단")
    void barsOutsideWindowDoNotLeakIntoMfeMae() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.put(WINDOW.get(2).plusDays(1), bar(WINDOW.get(2).plusDays(1), "20000", "5000", "9000", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(5), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.mfePct()).isEqualByComparingTo("6.0000");
        assertThat(r.maePct()).isEqualByComparingTo("-3.0000");
    }

    @Test
    @DisplayName("창은 정확히 3개 거래일이어야 한다")
    void windowMustBeExactlyThree() {
        assertThatThrownBy(() -> SignalD3Evaluator.evaluate(D0, List.of(D0), P0, BM0, normalBars(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== ③ 위장하지 않는다 ====================

    @Test
    @DisplayName("창 안 거래량 0 봉 = 동결가 → HALTED_IN_WINDOW, 수익률 0% 로 위장하지 않는다")
    void haltedBarIsNotZeroReturn() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.put(WINDOW.get(1), bar(WINDOW.get(1), "10100", "10100", "10100", "0"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.HALTED_IN_WINDOW);
        assertThat(r.pctChange()).isNull();
    }

    @Test
    @DisplayName("거래량 null 은 미수집이지 0 이 아니다 — 평가는 진행한다(§4c)")
    void nullVolumeIsNotHalt() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.put(WINDOW.get(1), bar(WINDOW.get(1), "10600", "10000", "10500", null));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
    }

    @Test
    @DisplayName("수정주가 소급 보정(5:1 병합 뒤 봉이 1/5) → UNIT_MISMATCH_SUSPECT, -80% 손실로 위장하지 않는다")
    void adjustedBarsAfterSplitAreFlaggedNotCountedAsLoss() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = new HashMap<>();
        // 시그널 뒤 액면분할 1:5 → KIS 가 과거 봉을 1/5 로 소급 — D0 종가 2,010 vs 기록가 10,000
        bars.put(D0, bar(D0, "2020", "1980", "2010", "1000"));
        bars.put(WINDOW.get(0), bar(WINDOW.get(0), "2040", "1940", "2020", "1000"));
        bars.put(WINDOW.get(1), bar(WINDOW.get(1), "2120", "2000", "2100", "1000"));
        bars.put(WINDOW.get(2), bar(WINDOW.get(2), "2080", "2020", "2060", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.UNIT_MISMATCH_SUSPECT);
        assertThat(r.note()).contains("0.201");
        assertThat(r.pctChange()).isNull();
    }

    @Test
    @DisplayName("D0 봉이 없으면 D+1 종가로 판정하되 두 세션 폭(0.45~1.75)을 쓴다 — 정상 +25% 는 통과")
    void unitCheckFallsBackToD1WithWiderBand() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.remove(D0);
        bars.put(WINDOW.get(0), bar(WINDOW.get(0), "12600", "11000", "12500", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
    }

    @Test
    @DisplayName("액면변경 감지기가 표시한 종목은 봉이 멀쩡해 보여도 CORPORATE_ACTION_SUSPECT")
    void corporateActionLabelWins() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), new BigDecimal("3030"), "액면병합 5:1 의심");

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.CORPORATE_ACTION_SUSPECT);
        assertThat(r.note()).contains("액면병합");
    }

    @Test
    @DisplayName("기록 시점 가격이 없으면 NO_START_PRICE — 복원하지 않는다")
    void noStartPrice() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, null, BM0, normalBars(), new BigDecimal("3030"), null);
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NO_START_PRICE);
    }

    // ==================== ④ 값과 지수 ====================

    @Test
    @DisplayName("정상: pct/mfe/mae/지수수익/알파/hit 이 정의대로 계산된다")
    void okNumbers() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), new BigDecimal("3030"), null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
        assertThat(r.endDate()).isEqualTo(WINDOW.get(2));
        assertThat(r.close()).isEqualByComparingTo("10300");
        assertThat(r.pctChange()).isEqualByComparingTo("3.0000");
        assertThat(r.mfePct()).isEqualByComparingTo("6.0000");
        assertThat(r.maePct()).isEqualByComparingTo("-3.0000");
        assertThat(r.bmClose()).isEqualByComparingTo("3030");
        assertThat(r.bmReturn()).isEqualByComparingTo("1.0000");
        assertThat(r.alpha()).isEqualByComparingTo("2.0000");
        assertThat(r.hit()).isTrue();
    }

    @Test
    @DisplayName("지수 종가가 없으면 NO_INDEX — 절대수익은 남기고 hit 은 기존 폴백 규칙(pct≥3%)")
    void noIndexUsesFallbackHitRule() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), null, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NO_INDEX);
        assertThat(r.pctChange()).isEqualByComparingTo("3.0000");
        assertThat(r.alpha()).isNull();
        assertThat(r.bmClose()).isNull();
        assertThat(r.hit()).isTrue();   // 3.0 ≥ 3 폴백

        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.put(WINDOW.get(2), bar(WINDOW.get(2), "10400", "10100", "10200", "1000"));   // +2%
        var r2 = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, null, null);
        assertThat(r2.hit()).isFalse();
    }

    @Test
    @DisplayName("hit 규칙은 기존 평가와 같은 단일 출처 — 알파 음수면 절대수익 (+)여도 miss")
    void hitRuleIsSharedWithLegacyEvaluation() {
        // 지수 +5% 인데 종목 +3% → alpha -2 → miss
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), new BigDecimal("3150"), null);
        assertThat(r.alpha()).isEqualByComparingTo("-2.0000");
        assertThat(r.hit()).isFalse();
    }
}
