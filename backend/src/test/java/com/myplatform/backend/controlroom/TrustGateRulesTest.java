package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "믿고 사도 되나" 게이트 — {@link TrustGateRules}.
 *
 * <p>이 테스트가 고정하는 핵심은 <b>통과 조건이 표본 크기가 아니라는 것</b>이다. 30건·10일은
 * "이제 숫자를 읽을 수 있다"까지만 데려가고, 거기서부터 비용·우위·불확실성·낙폭이 따로 걸린다.
 */
class TrustGateRulesTest {

    private static TrustGateRules.DayPair pair(int dayOffset, String signal, String control) {
        return new TrustGateRules.DayPair(
                LocalDate.of(2026, 9, 1).plusDays(dayOffset),
                new BigDecimal(signal), new BigDecimal(control));
    }

    /** 같은 값이 n 일 반복되는 짝 — 분산 0 이라 불확실성도 0 이 된다(경계 확인용). */
    private static List<TrustGateRules.DayPair> flat(int days, String signal, String control) {
        List<TrustGateRules.DayPair> out = new ArrayList<>();
        for (int i = 0; i < days; i++) out.add(pair(i, signal, control));
        return out;
    }

    /** 평균은 같되 날마다 흔들리는 짝 — 실제 데이터에 가깝다. */
    private static List<TrustGateRules.DayPair> wobbly(int days, double signalMean, double spread) {
        List<TrustGateRules.DayPair> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            double wobble = (i % 2 == 0) ? spread : -spread;
            out.add(new TrustGateRules.DayPair(LocalDate.of(2026, 9, 1).plusDays(i),
                    BigDecimal.valueOf(signalMean + wobble), BigDecimal.ZERO));
        }
        return out;
    }

    private static final TrustGateRules.Shape HEALTHY_SHAPE = new TrustGateRules.Shape(
            new BigDecimal("4.00"), new BigDecimal("-2.00"),
            new BigDecimal("-6.00"), new BigDecimal("-3.00"));

    // ==================== ① 표본 게이트 ====================

    @Test
    @DisplayName("행 수는 찼는데 고유 거래일이 모자라면 수집 중 — 같은 날 30건은 30번의 기회가 아니다")
    void rowsWithoutDistinctDaysStayCollecting() {
        var v = TrustGateRules.judge(flat(3, "5.0", "0.0"), 30, 30, HEALTHY_SHAPE, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.blockers()).anyMatch(b -> b.contains("고유 거래일 3/10"));
    }

    @Test
    @DisplayName("대조군 표본이 모자라도 수집 중 — 비교 상대가 없으면 우위를 말할 수 없다")
    void missingControlSampleStaysCollecting() {
        var v = TrustGateRules.judge(flat(12, "5.0", "0.0"), 40, 8, HEALTHY_SHAPE, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.blockers()).anyMatch(b -> b.contains("대조군 표본 8/30"));
    }

    // ==================== ② 표본 충족 ≠ 통과 ====================

    @Test
    @DisplayName("표본만 차고 비용 차감 수익이 음수면 '평가 가능'에서 멈춘다 — 통과 아님")
    void sufficientSampleWithNegativeReturnIsOnlyEvaluable() {
        var v = TrustGateRules.judge(wobbly(12, -1.85, 1.0), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-2.03");
        assertThat(v.blockers()).anyMatch(b -> b.contains("비용 차감 수익"));
    }

    @Test
    @DisplayName("비용 0.18%p 가 실제로 깎인다 — 총수익 0.10% 는 비용 차감 후 음수다")
    void costIsActuallySubtracted() {
        var v = TrustGateRules.judge(wobbly(12, 0.10, 0.5), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("-0.08");
        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
    }

    @Test
    @DisplayName("우위가 불확실성 폭을 못 넘으면 통과하지 않는다 — 흔들림 큰 +0.5%p 는 0 과 구분 안 됨")
    void edgeInsideUncertaintyDoesNotPass() {
        // 평균 우위 +0.68%p 인데 날마다 ±8%p 로 흔들린다 → 오차 폭이 우위보다 크다
        var v = TrustGateRules.judge(wobbly(12, 0.68, 8.0), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.edgeVsControl()).isEqualByComparingTo("0.68");
        assertThat(v.edgeMarginOfError()).isNotNull();
        assertThat(v.edgeMarginOfError()).isGreaterThan(v.edgeVsControl());
        assertThat(v.edgeExceedsUncertainty()).isFalse();
        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
    }

    @Test
    @DisplayName("평균 낙폭이 한도보다 깊으면 수익이 (+)여도 통과하지 않는다")
    void deepDrawdownBlocksEvenWithPositiveReturn() {
        var deep = new TrustGateRules.Shape(new BigDecimal("6.00"), new BigDecimal("-7.00"),
                new BigDecimal("-20.00"), new BigDecimal("-12.50"));

        var v = TrustGateRules.judge(wobbly(12, 3.0, 0.5), 40, 40, deep, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(v.blockers()).anyMatch(b -> b.contains("평균 최대낙폭"));
    }

    // ==================== ③ 통과 조건 ====================

    @Test
    @DisplayName("전부 충족해야 '모의운용 확대 검토' — 그마저 실매수 승인이 아니다")
    void allConditionsMetReachesConsiderExpanding() {
        var v = TrustGateRules.judge(wobbly(12, 3.0, 0.5), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.CONSIDER_EXPANDING);
        assertThat(v.blockers()).isEmpty();
        assertThat(v.costAdjustedReturn()).isEqualByComparingTo("2.82");
        assertThat(v.edgeExceedsUncertainty()).isTrue();
        assertThat(v.headline()).contains("실매수 승인 아님");
    }

    @Test
    @DisplayName("통과 문구에도 과거 성적이 미래를 보장하지 않는다고 적힌다")
    void passingVerdictStillCarriesTheCaveat() {
        var v = TrustGateRules.judge(wobbly(12, 3.0, 0.5), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.detail()).contains("미래 수익을 보장하지 않는다");
    }

    // ==================== ④ §4c — 모르는 것을 0 으로 위장하지 않는다 ====================

    @Test
    @DisplayName("하루치뿐이면 불확실성이 null — 0 으로 두면 어떤 미세한 우위도 '확실'해진다")
    void singleDayHasUnknownUncertaintyNotZero() {
        var v = TrustGateRules.judge(flat(1, "5.0", "0.0"), 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.edgeMarginOfError()).isNull();
        assertThat(v.edgeExceedsUncertainty()).isFalse();
        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
    }

    @Test
    @DisplayName("짝이 하나도 없으면 수익·우위는 null 이고 수집 중이다")
    void noPairsYieldsNullsNotZeros() {
        var v = TrustGateRules.judge(List.of(), 0, 0, null, 0);

        assertThat(v.costAdjustedReturn()).isNull();
        assertThat(v.edgeVsControl()).isNull();
        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.distinctDays()).isZero();
    }

    @Test
    @DisplayName("한쪽만 있는 날은 짝에서 빠진다 — 비교 불가인 날을 0 으로 채우지 않는다")
    void halfPairsAreDropped() {
        List<TrustGateRules.DayPair> pairs = new ArrayList<>(flat(4, "5.0", "0.0"));
        pairs.add(new TrustGateRules.DayPair(LocalDate.of(2026, 9, 20), new BigDecimal("9.0"), null));
        pairs.add(new TrustGateRules.DayPair(LocalDate.of(2026, 9, 21), null, new BigDecimal("1.0")));

        var v = TrustGateRules.judge(pairs, 40, 40, HEALTHY_SHAPE, 0);

        assertThat(v.distinctDays()).isEqualTo(4);
    }

    @Test
    @DisplayName("대조군 짝이 없어 빠진 날은 설명에 드러난다 — 조용한 제외 금지(§4c)")
    void excludedDaysAreSurfaced() {
        var v = TrustGateRules.judge(wobbly(12, 3.0, 0.5), 40, 40, HEALTHY_SHAPE, 5);

        assertThat(v.excludedDays()).isEqualTo(5);
        assertThat(v.detail()).contains("빠진 날 5일");
    }

    // ==================== ⑤ 운영 실측 고정 (2026-09-16) ====================

    @Test
    @DisplayName("2026-09-16 운영 실측: 9/7~9/10 4일·20행 — 통과는커녕 표본조차 못 채운다")
    void productionSnapshot20260916IsStillCollecting() {
        // 실측: BUY 20행 / 고유 4일 / 대조군 8행, 비용차감 -2.03%, 대조군은 +0.61%
        var shape = new TrustGateRules.Shape(new BigDecimal("5.12"), new BigDecimal("-6.50"),
                new BigDecimal("-12.41"), new BigDecimal("-5.42"));

        var v = TrustGateRules.judge(List.of(
                pair(0, "-1.20", "0.50"), pair(1, "-3.10", "1.40"),
                pair(2, "-0.90", "-0.30"), pair(3, "-2.20", "1.55")),
                20, 8, shape, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.blockers())
                .anyMatch(b -> b.contains("시그널 표본 20/30"))
                .anyMatch(b -> b.contains("대조군 표본 8/30"))
                .anyMatch(b -> b.contains("고유 거래일 4/10"));
        assertThat(v.costAdjustedReturn()).isNegative();
        // 대조군이 앞서고 있으므로 우위는 음수 — 통과 방향이 아니다
        assertThat(v.edgeVsControl()).isNegative();
        assertThat(v.edgeExceedsUncertainty()).isFalse();
    }

    @Test
    @DisplayName("비용 상수는 실제 수수료·거래세와 같다 — 값이 바뀌면 여기서 걸린다")
    void costConstantMatchesRealFees() {
        assertThat(TrustGateRules.ROUND_TRIP_COST_PCT).isEqualByComparingTo("0.18");
    }
}
