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
 * 밀리지 않는다 ③ 모르는 것은 사유로 남기고 0 이나 위장값을 쓰지 않는다(§4c) ④ 단위 검사의
 * 수학적 전제와 한계(2026-09-17 코덱스 리뷰).
 */
class SignalD3EvaluatorTest {

    // 2026-09-07(월) 시그널 → D+1 9/8, D+2 9/9, D+3 9/10
    private static final LocalDate D0 = LocalDate.of(2026, 9, 7);
    private static final List<LocalDate> WINDOW = List.of(
            LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10));
    private static final BigDecimal P0 = new BigDecimal("10000");
    private static final BigDecimal BM0 = new BigDecimal("3000.00");
    private static final BigDecimal IDX3 = new BigDecimal("3030");

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

    /** 정상 봉을 비율 k 로 소급 보정한 것처럼 만든다(수정주가). */
    private static Map<LocalDate, SignalD3Evaluator.Bar> scaledBars(String k) {
        BigDecimal f = new BigDecimal(k);
        Map<LocalDate, SignalD3Evaluator.Bar> out = new HashMap<>();
        for (var e : normalBars().entrySet()) {
            var b = e.getValue();
            out.put(e.getKey(), new SignalD3Evaluator.Bar(b.date(), b.high().multiply(f), b.low().multiply(f),
                    b.close().multiply(f), b.volume()));
        }
        return out;
    }

    // ==================== ① 실행 시각과 무관 ====================

    @Test
    @DisplayName("정상 평가(D+3 당일)와 열흘 늦은 평가가 같은 값을 낸다 — 배치가 밀려도 수익률이 안 바뀐다")
    void delayedEvaluationEqualsOnTimeEvaluation() {
        var onTime = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), IDX3, null);
        var late = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(10), WINDOW, P0, BM0, normalBars(), IDX3, null);

        assertThat(onTime.status()).isEqualTo(SignalD3Evaluator.Status.OK);
        assertThat(late).isEqualTo(onTime);
    }

    @Test
    @DisplayName("D+3 이 아직 안 지났으면 NOT_DUE — 저장하지 않는다")
    void notDueBeforeD3() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(1), WINDOW, P0, BM0, normalBars(), IDX3, null);
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

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(5), WINDOW, P0, BM0, bars, IDX3, null);

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

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2).plusDays(5), WINDOW, P0, BM0, bars, IDX3, null);

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

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.HALTED_IN_WINDOW);
        assertThat(r.pctChange()).isNull();
    }

    @Test
    @DisplayName("거래량 null 은 미수집이지 0 이 아니다 — 평가는 진행한다(§4c)")
    void nullVolumeIsNotHalt() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.put(WINDOW.get(1), bar(WINDOW.get(1), "10600", "10000", "10500", null));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
    }

    @Test
    @DisplayName("액면변경 감지기가 표시한 종목은 봉이 멀쩡해 보여도 CORPORATE_ACTION_SUSPECT")
    void corporateActionLabelWins() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), IDX3, "액면병합 5:1 의심");

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.CORPORATE_ACTION_SUSPECT);
        assertThat(r.note()).contains("액면병합");
    }

    @Test
    @DisplayName("기록 시점 가격이 없으면 NO_START_PRICE — 복원하지 않는다")
    void noStartPrice() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, null, BM0, normalBars(), IDX3, null);
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NO_START_PRICE);
    }

    // ==================== ④ 단위 검사 — 수학적 전제와 한계 ====================

    @Test
    @DisplayName("수정주가 소급 보정(5:1 → 봉이 1/5) → UNIT_MISMATCH_SUSPECT, -80% 손실로 위장하지 않는다")
    void adjustedBarsAfterSplitAreFlaggedNotCountedAsLoss() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, scaledBars("0.2"), IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.UNIT_MISMATCH_SUSPECT);
        assertThat(r.note()).contains("0.201");
        assertThat(r.pctChange()).isNull();
    }

    @Test
    @DisplayName("코덱스 반례: 전일 100·기록가 80·당일 종가 115(비율 1.4375)는 정상 변동 — 액면변경으로 제외하면 안 된다")
    void legitimateIntradayMoveIsNotFlagged() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        // 기록가 8,000(전일 10,000 의 -20%), D0 종가 11,500(+15%) — 둘 다 상하한 안, 비율 1.4375
        bars.put(D0, bar(D0, "11600", "7900", "11500", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, new BigDecimal("8000"), BM0, bars, IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
    }

    @Test
    @DisplayName("2:1 분할 소급(비율 0.5)은 가격이 안 움직였을 때만 하한 0.53 아래로 걸린다 — 경계값")
    void twoForOneSplitIsCaughtOnlyWhenPriceDidNotMove() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, scaledBars("0.5"), IDX3, null);
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.UNIT_MISMATCH_SUSPECT);
    }

    @Test
    @DisplayName("한계 고정(사용자 반례): 정상 변동 1.2 × 2:1 조정 0.5 = 0.6 은 통과한다 — 2:1 도 놓칠 수 있다")
    void twoForOneSplitIsMissedWhenPriceMovedUp() {
        // D0 에 +20% 오른 뒤 2:1 분할이 소급 보정됨 → D0 종가 10,050×1.2×0.5 = 6,030, 비율 0.603
        Map<LocalDate, SignalD3Evaluator.Bar> bars = scaledBars("0.6");

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, IDX3, null);

        // 이 테스트가 깨지면 검사가 정교해진 것이다 — 그때 문서의 "놓칠 수 있다"도 같이 고칠 것.
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
        assertThat(r.pctChange()).isNegative();   // 왜곡된 손실이 OK 로 남는다
    }

    @Test
    @DisplayName("한계 고정: 3:2 병합 소급(비율 0.667)은 정상 범위 안이라 못 잡는다 — 검사는 거친 안전망이다")
    void smallRatioAdjustmentIsNotCaughtKnownLimitation() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, scaledBars("0.6667"), IDX3, null);

        // 이 테스트가 깨지면 검사가 정교해진 것이다 — 그때 문서의 "못 잡는다"도 같이 고칠 것.
        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
        assertThat(r.pctChange()).isNegative();   // 왜곡된 손실이 OK 로 남는다
    }

    @Test
    @DisplayName("D0 봉이 없으면 D+1 종가로 판정하되 두 세션 폭(0.37~2.45)을 쓴다 — 정상 +60% 는 통과")
    void unitCheckFallsBackToD1WithWiderBand() {
        Map<LocalDate, SignalD3Evaluator.Bar> bars = normalBars();
        bars.remove(D0);
        bars.put(WINDOW.get(0), bar(WINDOW.get(0), "16100", "11000", "16000", "1000"));

        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, bars, IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.OK);
    }

    // ==================== ⑤ 값과 지수 ====================

    @Test
    @DisplayName("정상: pct/mfe/mae/지수수익/알파/hit 이 정의대로 계산된다")
    void okNumbers() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), IDX3, null);

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
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("지수 종가가 없으면 NO_INDEX = 미평가 — 절대수익도 hit 도 남기지 않는다(비교표에서 빠진다)")
    void noIndexIsUnevaluatedNotPartial() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, BM0, normalBars(), null, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NO_INDEX);
        assertThat(r.pctChange()).isNull();
        assertThat(r.mfePct()).isNull();
        assertThat(r.hit()).isNull();
        assertThat(r.note()).contains("재시도");
    }

    @Test
    @DisplayName("기록 시점 지수가 없으면 NO_INDEX 인데 사유는 '복원 불가' — 재시도해도 안 풀린다")
    void noStartIndexIsUnrecoverable() {
        var r = SignalD3Evaluator.evaluate(WINDOW.get(2), WINDOW, P0, null, normalBars(), IDX3, null);

        assertThat(r.status()).isEqualTo(SignalD3Evaluator.Status.NO_INDEX);
        assertThat(r.note()).contains("복원 불가");
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
