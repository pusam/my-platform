package com.myplatform.backend.controlroom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "봇을 믿고 맡겨도 되나" 판정 — {@link BotGateRules}(2026-10-02).
 *
 * <p>핵심 셋: ① 표본이 차도 통과가 아니다(추천 게이트와 같은 3단계) ② 유효 표본은 거래 수가 아니라 거래일 수 —
 * 하루에 몰린 거래가 평균을 끌고 가지 않는다 ③ 위험(최악 1건·최대 낙폭) 한도는 평균이 좋아도 따로 막는다.
 */
class BotGateRulesTest {

    private static final LocalDate D0 = LocalDate.of(2026, 7, 1);

    private static BotGateRules.TradeOutcome t(int dayOffset, String strategy, String pct) {
        return new BotGateRules.TradeOutcome(D0.plusDays(dayOffset), strategy, new BigDecimal(pct));
    }

    /** {@code days}일 × 하루 {@code perDay}건, 수익률은 날마다 주어진 값을 순서대로. */
    private static List<BotGateRules.TradeOutcome> spread(int days, int perDay, String... dailyPcts) {
        List<BotGateRules.TradeOutcome> out = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            for (int i = 0; i < perDay; i++) out.add(t(d, "SCALPING", dailyPcts[d % dailyPcts.length]));
        }
        return out;
    }

    @Test
    @DisplayName("거래가 없으면 표본 수집 중 — 평균·불확실성은 0 이 아니라 null(§4c)")
    void emptyIsCollectingWithNullsNotZeros() {
        BotGateRules.Verdict v = BotGateRules.judge(List.of(), null, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.trades()).isZero();
        assertThat(v.dailyMeanPct()).isNull();
        assertThat(v.marginOfError()).isNull();
        assertThat(v.worstPct()).isNull();
        assertThat(v.blockers()).containsExactly("거래 0/30건", "거래일 0/10일");
        assertThat(v.headline()).startsWith("표본 수집 중 — ");
    }

    @Test
    @DisplayName("거래 30건을 채워도 거래일이 10일 미만이면 표본 수집 중 — 같은 날 거래는 독립 표본이 아니다")
    void thirtyTradesInFewDaysIsStillCollecting() {
        BotGateRules.Verdict v = BotGateRules.judge(spread(5, 8, "1.0"), null, 0);

        assertThat(v.trades()).isEqualTo(40);
        assertThat(v.distinctDays()).isEqualTo(5);
        assertThat(v.state()).isEqualTo(TrustGateRules.State.COLLECTING);
        assertThat(v.blockers()).containsExactly("거래일 5/10일");
    }

    @Test
    @DisplayName("하루에 몰린 거래가 평균을 끌고 가지 않는다 — 거래일별 평균의 평균")
    void heavyDayDoesNotDominateTheMean() {
        List<BotGateRules.TradeOutcome> trades = new ArrayList<>();
        for (int i = 0; i < 10; i++) trades.add(t(0, "SCALPING", "5"));   // 첫날 +5% 10건
        trades.add(t(1, "SCALPING", "-5"));                                // 둘째 날 -5% 1건

        BotGateRules.Verdict v = BotGateRules.judge(trades, null, 0);

        // 거래 단위 평균이면 +4.09% 로 보이지만, 하루를 한 표본으로 접으면 (+5 + -5) / 2 = 0
        assertThat(v.dailyMeanPct()).isEqualByComparingTo("0.00");
        assertThat(v.winRatePct()).isEqualByComparingTo("90.9");
    }

    @Test
    @DisplayName("표본이 차고 하루 평균이 (−)면 평가 가능 — 막는 사유에 그 숫자를 적는다(7월 모의 성적 모양)")
    void negativeMeanWithEnoughSampleIsEvaluableNotPassing() {
        BotGateRules.Verdict v = BotGateRules.judge(spread(16, 7, "-0.8", "0.4", "-1.2", "0.1"), null, 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(v.trades()).isEqualTo(112);
        assertThat(v.distinctDays()).isEqualTo(16);
        assertThat(v.dailyMeanPct()).isEqualByComparingTo("-0.38");
        assertThat(v.blockers()).containsExactly("하루 평균 순수익 -0.38%");
        assertThat(v.headline()).isEqualTo("평가 가능 — 아직 근거 없음: 하루 평균 순수익 -0.38%");
    }

    @Test
    @DisplayName("하루 평균이 (+)여도 불확실성 폭 안이면 0 과 구분되지 않는다 — 통과 아님")
    void positiveButWithinUncertaintyIsNotPassing() {
        BotGateRules.Verdict v = BotGateRules.judge(spread(10, 3, "3.0", "-2.8"), null, 0);

        assertThat(v.dailyMeanPct()).isEqualByComparingTo("0.10");
        assertThat(v.profitExceedsUncertainty()).isFalse();
        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(v.blockers()).hasSize(1);
        assertThat(v.blockers().get(0)).isEqualTo("하루 평균 +0.10% 가 불확실성 ±1.89% 를 못 넘음");
    }

    @Test
    @DisplayName("(+)가 불확실성 폭을 넘고 위험 한도 안이면 확대 검토 가능 — 그래도 '승인 아님'")
    void clearProfitWithinRiskLimitsConsidersExpanding() {
        BotGateRules.Verdict v = BotGateRules.judge(spread(12, 3, "0.9", "1.1", "1.0"), new BigDecimal("-3.5"), 0);

        assertThat(v.state()).isEqualTo(TrustGateRules.State.CONSIDER_EXPANDING);
        assertThat(v.profitExceedsUncertainty()).isTrue();
        assertThat(v.blockers()).isEmpty();
        assertThat(v.headline()).isEqualTo("확대 검토 가능 — 승인 아님");
        assertThat(v.detail()).contains("실전 승인이 아니다");
    }

    @Test
    @DisplayName("평균이 좋아도 최악 1건이 −10% 보다 깊으면 막는다 — 손절이 새고 있다")
    void worstTradeBeyondLimitBlocks() {
        List<BotGateRules.TradeOutcome> trades = new ArrayList<>(spread(12, 3, "0.9", "1.1", "1.0"));
        trades.add(t(3, "SWING", "-12.4"));

        BotGateRules.Verdict v = BotGateRules.judge(trades, new BigDecimal("-3.5"), 0);

        assertThat(v.worstPct()).isEqualByComparingTo("-12.40");
        assertThat(v.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(v.blockers()).anyMatch(b -> b.startsWith("최악 거래 -12.40%"));
    }

    @Test
    @DisplayName("자본 대비 최대 낙폭이 −10% 보다 깊으면 막는다. 자본을 모르면(null) 판정하지 않고 그렇게 적는다")
    void drawdownLimitAndUnknownCapital() {
        List<BotGateRules.TradeOutcome> good = spread(12, 3, "0.9", "1.1", "1.0");

        BotGateRules.Verdict deep = BotGateRules.judge(good, new BigDecimal("-11.2"), 0);
        assertThat(deep.state()).isEqualTo(TrustGateRules.State.EVALUABLE);
        assertThat(deep.blockers()).containsExactly("최대 낙폭 -11.20% (자본 대비, 한도 -10%)");

        BotGateRules.Verdict unknown = BotGateRules.judge(good, null, 0);
        assertThat(unknown.maxDrawdownPct()).isNull();
        assertThat(unknown.state()).isEqualTo(TrustGateRules.State.CONSIDER_EXPANDING);
        assertThat(unknown.detail()).contains("자본을 몰라 최대 낙폭은 판정하지 않았다");
    }

    @Test
    @DisplayName("전략별 줄은 정해진 순서로, 모르는 전략 이름은 UNKNOWN 으로 모은다")
    void strategyLinesInFixedOrder() {
        List<BotGateRules.TradeOutcome> trades = List.of(
                t(0, "SWING", "2"), t(1, "SWING", "-1"),
                t(0, "SCALPING", "-0.5"),
                t(2, "WHATEVER", "1"));

        BotGateRules.Verdict v = BotGateRules.judge(trades, null, 0);

        assertThat(v.strategies()).extracting(BotGateRules.StrategyLine::strategy)
                .containsExactly("SCALPING", "SWING", "UNKNOWN");
        BotGateRules.StrategyLine swing = v.strategies().get(1);
        assertThat(swing.trades()).isEqualTo(2);
        assertThat(swing.distinctDays()).isEqualTo(2);
        assertThat(swing.dailyMeanPct()).isEqualByComparingTo("0.50");
        assertThat(swing.winRatePct()).isEqualByComparingTo("50.0");
    }

    @Test
    @DisplayName("수익률을 못 구해 뺀 매도는 조용히 사라지지 않고 설명에 남는다(§4c). null 항목은 무시")
    void excludedTradesAreDeclaredAndNullsIgnored() {
        List<BotGateRules.TradeOutcome> trades = new ArrayList<>();
        trades.add(null);
        trades.add(new BotGateRules.TradeOutcome(D0, "SCALPING", null));
        trades.add(t(0, "SCALPING", "1"));

        BotGateRules.Verdict v = BotGateRules.judge(trades, null, 3);

        assertThat(v.trades()).isEqualTo(1);
        assertThat(v.excludedTrades()).isEqualTo(3);
        assertThat(v.detail()).contains("뺀 매도 3건");
        assertThat(BotGateRules.judge(null, null, -1).excludedTrades()).isZero();
    }

    @Test
    @DisplayName("설명문은 시장 대비 비교가 없다는 것과 모의의 낙관(슬리피지 없음)을 밝힌다")
    void detailDeclaresMissingMarketBaseline() {
        BotGateRules.Verdict v = BotGateRules.judge(spread(3, 2, "1"), null, 0);

        assertThat(v.detail())
                .contains("시장 대비 비교가 없다")
                .contains("슬리피지가 없다")
                .contains("현재 3일");
    }
}
