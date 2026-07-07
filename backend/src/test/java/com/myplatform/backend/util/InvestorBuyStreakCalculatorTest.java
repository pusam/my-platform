package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연속 순매수일 순수 함수 테스트 — 표시·참고 전용(산식 미편입).
 * 핵심: 거래일 캘린더 맨 앞부터 연속 매수일만 카운트, 주말갭 오인 없음, 데이터 부족=null(§4c).
 */
class InvestorBuyStreakCalculatorTest {

    private static final LocalDate D(int day) { return LocalDate.of(2026, 7, day); }

    // 캘린더: 7/6,7/3,7/2,7/1,6/30 (7/4·7/5 주말 없음 = 실제 거래일만)
    private static final List<LocalDate> CAL = List.of(D(6), D(3), D(2), D(1), LocalDate.of(2026, 6, 30));

    @Test
    @DisplayName("최신일부터 연속 매수 → 그 길이. 주말 갭(7/4,7/5 부재)은 갭 아님")
    void streak_countsFromLatest() {
        // 7/6,7/3,7/2 매수 → 7/1 매수 아님 → streak=3
        Integer s = InvestorBuyStreakCalculator.consecutiveNetBuyDays(CAL, Set.of(D(6), D(3), D(2)));
        assertThat(s).isEqualTo(3);
    }

    @Test
    @DisplayName("최신 거래일에 매수 없으면 streak=0 (연속 아님)")
    void streak_zeroWhenNotBoughtLatest() {
        // 7/6 매수 아님 → 0 (그 이전 매수는 무의미)
        Integer s = InvestorBuyStreakCalculator.consecutiveNetBuyDays(CAL, Set.of(D(3), D(2), D(1)));
        assertThat(s).isZero();
    }

    @Test
    @DisplayName("전 거래일 매수 → 캘린더 전체 길이")
    void streak_allDays() {
        Integer s = InvestorBuyStreakCalculator.consecutiveNetBuyDays(
                CAL, Set.of(D(6), D(3), D(2), D(1), LocalDate.of(2026, 6, 30)));
        assertThat(s).isEqualTo(5);
    }

    @Test
    @DisplayName("거래일 5일 미만이면 null (데이터 부족 — 0 위장 금지, §4c)")
    void streak_nullWhenInsufficientData() {
        List<LocalDate> shortCal = List.of(D(6), D(3), D(2), D(1));   // 4일
        assertThat(InvestorBuyStreakCalculator.consecutiveNetBuyDays(shortCal, Set.of(D(6)))).isNull();
        assertThat(InvestorBuyStreakCalculator.consecutiveNetBuyDays(null, Set.of(D(6)))).isNull();
    }

    @Test
    @DisplayName("매수 이력 없음 → 0 (데이터는 충분)")
    void streak_zeroWhenNoBuys() {
        assertThat(InvestorBuyStreakCalculator.consecutiveNetBuyDays(CAL, Set.of())).isZero();
        assertThat(InvestorBuyStreakCalculator.consecutiveNetBuyDays(CAL, null)).isZero();
    }

    @Test
    @DisplayName("캘린더 정렬 무관 — 내부에서 최신순 처리")
    void streak_unsortedCalendar() {
        List<LocalDate> unsorted = List.of(D(1), LocalDate.of(2026, 6, 30), D(6), D(2), D(3));
        Integer s = InvestorBuyStreakCalculator.consecutiveNetBuyDays(unsorted, Set.of(D(6), D(3)));
        assertThat(s).isEqualTo(2);   // 7/6,7/3 연속 → 7/2 매수 아님
    }
}
