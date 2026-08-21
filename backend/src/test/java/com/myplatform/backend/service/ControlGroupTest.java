package com.myplatform.backend.service;

import com.myplatform.backend.dto.SignalBandAccuracyDto;
import com.myplatform.backend.entity.SignalOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무작위 대조군(base rate) 검증 — P2-19 ④ 해소분.
 *
 * <p>가장 중요한 테스트는 <b>오염 방지</b>다. 대조군 행이 기존 밴드/카테고리 집계에 섞이면
 * 지금까지 쌓은 측정이 통째로 무의미해진다.
 */
class ControlGroupTest {

    private static final LocalDate D = LocalDate.of(2026, 8, 3);

    private static SignalOutcome signal(String type, Integer score, boolean hit, double pct) {
        return SignalOutcome.builder()
                .signalType(type).stockCode("00" + Math.abs(score == null ? 1 : score))
                .signalDate(D).signalScore(score)
                .priceAtSignal(BigDecimal.valueOf(10000))
                .hit(hit).pctChange3d(BigDecimal.valueOf(pct))
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * n 행 생성 — <b>최근 20일에 분산</b>한다(하루 뭉치기 방지).
     * 같은 날 행들은 같은 시장 충격을 공유해 독립 표본이 아니므로(P3-11 과 동일 원리),
     * 기본 픽스처를 현실적인 분산 형태로 둔다.
     */
    private static List<SignalOutcome> many(String type, Integer score, int n, int hits, double pct) {
        List<SignalOutcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SignalOutcome s = signal(type, score, i < hits, pct);
            s.setStockCode(String.format("%s%04d", type.charAt(0), i));
            s.setSignalDate(D.minusDays(i % 20));
            out.add(s);
        }
        return out;
    }

    /** 하루 1행씩 {@code days} 일 연속 — 고유일수를 정확히 통제하고 싶을 때. */
    private static List<SignalOutcome> daily(String type, Integer score, LocalDate start,
                                             int days, int hits, double pct) {
        List<SignalOutcome> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            SignalOutcome s = signal(type, score, i < hits, pct);
            s.setStockCode(String.format("%s%04d", type.charAt(0), i));
            s.setSignalDate(start.plusDays(i));
            out.add(s);
        }
        return out;
    }

    /** 전부 같은 날 — 행 수는 많지만 고유일수 1(독립 표본 아님) 재현용. */
    private static List<SignalOutcome> sameDay(String type, Integer score, int n, int hits, double pct) {
        List<SignalOutcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SignalOutcome s = signal(type, score, i < hits, pct);
            s.setStockCode(String.format("%s%04d", type.charAt(0), i));
            s.setSignalDate(D);
            out.add(s);
        }
        return out;
    }

    // ==================== 오염 방지 (최우선) ====================

    @Test
    void 대조군은_보드_시그널_필터에서_제외된다() {
        List<SignalOutcome> rows = new ArrayList<>();
        rows.addAll(many("STRONG_BUY", 80, 3, 1, 1.0));
        rows.addAll(many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 5, 4, 5.0));

        List<SignalOutcome> board = SignalOutcomeService.filterBoardSignals(rows);
        List<SignalOutcome> control = SignalOutcomeService.filterControlSignals(rows);

        assertThat(board).hasSize(3);
        assertThat(control).hasSize(5);
        assertThat(board).noneMatch(s -> ControlGroupService.isControl(s.getSignalType()));
    }

    @Test
    void 대조군_타입은_보드_타입과_교집합이_없다() {
        // 이 불변식이 깨지면 대조군이 밴드 집계를 오염시킨다
        Set<String> board = new HashSet<>(SignalOutcomeService.BOARD_SIGNAL_TYPES);
        board.retainAll(ControlGroupService.excludedFromBoardAggregation());
        assertThat(board).isEmpty();
    }

    @Test
    void 대조군이_섞여도_밴드_집계는_바뀌지_않는다() {
        List<SignalOutcome> boardOnly = many("STRONG_BUY", 80, 10, 4, 2.0);

        List<SignalOutcome> mixed = new ArrayList<>(boardOnly);
        // 대조군은 점수가 null 이라 밴드에 못 들어가지만, 혹시 점수가 실리더라도 걸러져야 한다
        mixed.addAll(many(ControlGroupService.CONTROL_SIGNAL_TYPE, 80, 50, 50, 9.9));

        var before = SignalOutcomeService.aggregateBands(boardOnly);
        var after = SignalOutcomeService.aggregateBands(
                SignalOutcomeService.filterBoardSignals(mixed));

        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i).getTotalSignals()).isEqualTo(before.get(i).getTotalSignals());
            assertThat(after.get(i).getHitRate()).isEqualTo(before.get(i).getHitRate());
        }
    }

    @Test
    void 대조군은_점수가_없어_밴드에_편입되지_않는다() {
        // 점수 null 이면 aggregateBands 가 이미 제외 — 이중 방어의 두 번째 층
        var bands = SignalOutcomeService.aggregateBands(
                many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 20, 20, 5.0));
        assertThat(bands).allMatch(b -> b.getTotalSignals() == 0);
    }

    // ==================== 비교 집계 ====================

    @Test
    void 표본이_충분하고_우위가_크면_유의하다고_판정한다() {
        var cmp = SignalOutcomeService.aggregateControlComparison(
                many("STRONG_BUY", 80, 100, 60, 3.0),      // 60%
                many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 100, 30, 0.5));  // 30%

        assertThat(cmp.getSignalCount()).isEqualTo(100);
        assertThat(cmp.getControlCount()).isEqualTo(100);
        assertThat(cmp.getEdgeHitRate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(cmp.isSignificant()).isTrue();
        assertThat(cmp.isInsufficientSample()).isFalse();
        assertThat(cmp.getVerdict()).contains("우위");
    }

    @Test
    void 차이가_작으면_무작위와_구분되지_않는다고_말한다() {
        var cmp = SignalOutcomeService.aggregateControlComparison(
                many("STRONG_BUY", 80, 100, 36, 1.0),      // 36%
                many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 100, 34, 1.0));  // 34%

        assertThat(cmp.isSignificant()).isFalse();
        assertThat(cmp.getVerdict()).contains("무작위와 구분되지 않음");
        assertThat(cmp.getVerdict()).contains("매수 근거");
    }

    @Test
    void 무작위보다_나쁘면_그렇게_말한다() {
        var cmp = SignalOutcomeService.aggregateControlComparison(
                many("STRONG_BUY", 80, 200, 40, -2.0),     // 20%
                many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 200, 100, 1.0)); // 50%

        assertThat(cmp.getEdgeHitRate()).isLessThan(BigDecimal.ZERO);
        assertThat(cmp.isSignificant()).isTrue();
        assertThat(cmp.getVerdict()).contains("나쁨");
    }

    @Test
    void 표본이_부족하면_판정을_보류한다() {
        var cmp = SignalOutcomeService.aggregateControlComparison(
                many("STRONG_BUY", 80, 5, 5, 9.0),         // 100% 지만 n=5
                many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 5, 0, -1.0));

        assertThat(cmp.isInsufficientSample()).isTrue();
        assertThat(cmp.isSignificant()).isFalse();       // 표본부족이면 유의 판정 안 함
        assertThat(cmp.getVerdict()).contains("표본 부족");
    }

    @Test
    void 미평가_행은_비교에서_제외된다() {
        List<SignalOutcome> boardRows = many("STRONG_BUY", 80, 40, 20, 1.0);
        boardRows.forEach(s -> s.setEvaluatedAt(null));   // 전부 미평가

        var cmp = SignalOutcomeService.aggregateControlComparison(
                boardRows, many(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 40, 10, 0.0));

        assertThat(cmp.getSignalCount()).isZero();
        assertThat(cmp.isInsufficientSample()).isTrue();
    }

    // ==================== 비교 창 정렬 (2026-08-21) ====================

    @Test
    void 대조군이_없는_기간의_시그널은_비교에서_제외된다() {
        // 대조군은 2026-08-05 도입이라 그 이전 시그널에는 짝이 없다. 창을 맞추지 않으면
        // 두 비율이 서로 다른 기간에서 계산돼 edge 가 '점수의 기여'가 아니라 '시장 차이'를 담는다.
        List<SignalOutcome> board = daily("STRONG_BUY", 80, LocalDate.of(2026, 6, 25), 60, 12, -1.0);
        List<SignalOutcome> control = daily(ControlGroupService.CONTROL_SIGNAL_TYPE, null,
                LocalDate.of(2026, 8, 5), 15, 5, 0.5);

        var cmp = SignalOutcomeService.aggregateControlComparison(board, control);

        assertThat(cmp.getComparisonFrom()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(cmp.getComparisonTo()).isEqualTo(LocalDate.of(2026, 8, 19));
        assertThat(cmp.getComparisonDays()).isEqualTo(15);
        assertThat(cmp.getSignalCount()).isEqualTo(15);   // 60건 전부가 아니라 공통 15일치만
        assertThat(cmp.getControlCount()).isEqualTo(15);
    }

    @Test
    void 겹치는_날짜가_없으면_비교_자체가_불가하다() {
        var cmp = SignalOutcomeService.aggregateControlComparison(
                daily("STRONG_BUY", 80, LocalDate.of(2026, 6, 25), 20, 10, 1.0),
                daily(ControlGroupService.CONTROL_SIGNAL_TYPE, null, LocalDate.of(2026, 8, 5), 20, 10, 1.0));

        assertThat(cmp.getComparisonDays()).isZero();
        assertThat(cmp.getComparisonFrom()).isNull();
        assertThat(cmp.getComparisonTo()).isNull();
        assertThat(cmp.getSignalCount()).isZero();
        assertThat(cmp.getControlCount()).isZero();
        assertThat(cmp.isInsufficientSample()).isTrue();
    }

    @Test
    void 같은_날_뭉친_표본은_고유일수_부족으로_판정을_보류한다() {
        // 행 수만 보면 각 100건이라 z≈4.3(유의)이지만, 고유일수 1일이면 독립 표본이 아니다.
        // P3-11 이 지수 축에 적용한 distinctDays 원리를 대조군 비교에도 적용한다.
        var cmp = SignalOutcomeService.aggregateControlComparison(
                sameDay("STRONG_BUY", 80, 100, 60, 3.0),
                sameDay(ControlGroupService.CONTROL_SIGNAL_TYPE, null, 100, 30, 0.5));

        assertThat(cmp.getComparisonDays()).isEqualTo(1);
        assertThat(cmp.isInsufficientSample()).isTrue();
        assertThat(cmp.isSignificant()).isFalse();
        assertThat(cmp.getVerdict()).contains("표본 부족");
    }

    // ==================== z검정 순수함수 ====================

    @Test
    void z검정_계산이_정확하다() {
        // p1=0.6(n=100), p2=0.3(n=100), pooled=0.45 → se=sqrt(.45*.55*.02)=0.0704 → z≈4.26
        Double z = SignalOutcomeService.twoProportionZ(60, 100, 30, 100);
        assertThat(z).isNotNull();
        assertThat(z).isCloseTo(4.26, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void z검정_계산불가는_null이다() {
        assertThat(SignalOutcomeService.twoProportionZ(0, 0, 0, 10)).isNull();   // 표본 0
        assertThat(SignalOutcomeService.twoProportionZ(0, 50, 0, 50)).isNull();  // 전부 미적중(pooled=0)
        assertThat(SignalOutcomeService.twoProportionZ(50, 50, 50, 50)).isNull(); // 전부 적중(pooled=1)
    }

    // ==================== 대조군 종목 선택 ====================

    @Test
    void 시그널_종목은_대조군에서_제외된다() {
        List<String> universe = List.of("000001", "000002", "000003");
        for (int attempt = 0; attempt < 20; attempt++) {
            String picked = ControlGroupService.pickControlStock(
                    universe, "000002", ControlGroupService.seedFor(D, "000002", attempt));
            assertThat(picked).isNotEqualTo("000002");
            assertThat(universe).contains(picked);
        }
    }

    @Test
    void 같은_시드는_같은_종목을_준다_재현성() {
        List<String> universe = List.of("000001", "000002", "000003", "000004", "000005");
        long seed = ControlGroupService.seedFor(D, "000009", 0);
        assertThat(ControlGroupService.pickControlStock(universe, "000009", seed))
                .isEqualTo(ControlGroupService.pickControlStock(universe, "000009", seed));
    }

    @Test
    void 시도횟수가_다르면_다른_종목을_시도한다() {
        List<String> universe = new ArrayList<>();
        for (int i = 0; i < 200; i++) universe.add(String.format("%06d", i));

        Set<String> picked = new HashSet<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            picked.add(ControlGroupService.pickControlStock(
                    universe, "999999", ControlGroupService.seedFor(D, "000001", attempt)));
        }
        // 8회 시도가 전부 같은 종목이면 재시도가 무의미해진다
        assertThat(picked.size()).isGreaterThan(1);
    }

    @Test
    void 유니버스가_비었거나_자기자신뿐이면_null() {
        assertThat(ControlGroupService.pickControlStock(List.of(), "000001", 1L)).isNull();
        assertThat(ControlGroupService.pickControlStock(null, "000001", 1L)).isNull();
        assertThat(ControlGroupService.pickControlStock(List.of("000001"), "000001", 1L)).isNull();
    }

    @Test
    void 선택이_유니버스_전체에_고르게_퍼진다() {
        List<String> universe = new ArrayList<>();
        for (int i = 0; i < 100; i++) universe.add(String.format("%06d", i));

        Set<String> picked = new HashSet<>();
        for (int day = 0; day < 300; day++) {
            picked.add(ControlGroupService.pickControlStock(universe, "999999",
                    ControlGroupService.seedFor(D.plusDays(day), "000001", 0)));
        }
        // 편향된 해시면 소수 종목에만 몰린다 — 300회에 최소 절반은 서로 달라야 한다
        assertThat(picked.size()).isGreaterThan(50);
    }
}
