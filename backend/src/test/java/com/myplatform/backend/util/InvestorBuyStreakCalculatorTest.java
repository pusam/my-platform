package com.myplatform.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연속 순매수일 순수 함수 테스트 — 표시·참고 전용(산식 미편입).
 *
 * <p>핵심 계약(AUDIT R3, 2026-08-31 통일): 휴장일은 정상 공백으로 건너뛰고,
 * <b>거래일인데 데이터가 없으면(수집 결측) 연속을 끊는다</b> — 이전 구현은 결측일을
 * 주말처럼 붙여 세서 "N일 연속" 배지를 부풀렸다(§4c 위반, 점수용은 2026-07-28 에 수정됨).
 */
class InvestorBuyStreakCalculatorTest {

    private static LocalDate D(int day) { return LocalDate.of(2026, 7, day); }

    /** 테스트용 휴장 판정 = 주말만 (2026-07-04 토 / 07-05 일). */
    private static final Predicate<LocalDate> WEEKEND =
            d -> d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;

    // 캘린더: 7/6(월),7/3(금),7/2(목),7/1(수),6/30(화) — 7/4·7/5 는 주말
    private static final List<LocalDate> CAL = List.of(D(6), D(3), D(2), D(1), LocalDate.of(2026, 6, 30));

    private static Integer calc(List<LocalDate> cal, Set<LocalDate> buys) {
        return InvestorBuyStreakCalculator.consecutiveNetBuyDays(cal, buys, WEEKEND);
    }

    @Test
    @DisplayName("최신일부터 연속 매수 → 그 길이. 주말 갭(7/4,7/5)은 갭 아님")
    void streak_countsFromLatest() {
        // 7/6,7/3,7/2 매수 → 7/1 매수 아님 → streak=3
        assertThat(calc(CAL, Set.of(D(6), D(3), D(2)))).isEqualTo(3);
    }

    @Test
    @DisplayName("R3 회귀 — 거래일 수집 결측은 연속을 끊는다(결측일을 주말처럼 붙이지 않는다)")
    void streak_missingTradingDayBreaks() {
        // 7/1(수) 데이터 결측 — 캘린더에 없다. 매수는 7/6,7/3,7/2,6/30 전부.
        // 옛 구현: 데이터 있는 날만 이어 세서 4 (6/30 까지 붙임 — 부풀림).
        // 새 계약: 7/2 다음 7/1 이 거래일인데 결측 → 3 에서 끊는다.
        List<LocalDate> calWithGap = List.of(D(6), D(3), D(2), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 6, 29));
        Integer s = calc(calWithGap, Set.of(D(6), D(3), D(2), LocalDate.of(2026, 6, 30)));
        assertThat(s).isEqualTo(3);
    }

    @Test
    @DisplayName("최신 거래일에 매수 없으면 streak=0 (연속 아님)")
    void streak_zeroWhenNotBoughtLatest() {
        assertThat(calc(CAL, Set.of(D(3), D(2), D(1)))).isZero();
    }

    @Test
    @DisplayName("전 거래일 매수 → 캘린더 전체 길이")
    void streak_allDays() {
        assertThat(calc(CAL, Set.of(D(6), D(3), D(2), D(1), LocalDate.of(2026, 6, 30))))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("거래일 5일 미만이면 null (데이터 부족 — 0 위장 금지, §4c)")
    void streak_nullWhenInsufficientData() {
        List<LocalDate> shortCal = List.of(D(6), D(3), D(2), D(1));   // 4일
        assertThat(calc(shortCal, Set.of(D(6)))).isNull();
        assertThat(calc(null, Set.of(D(6)))).isNull();
    }

    @Test
    @DisplayName("매수 이력 없음 → 0 (데이터는 충분)")
    void streak_zeroWhenNoBuys() {
        assertThat(calc(CAL, Set.of())).isZero();
        assertThat(calc(CAL, null)).isZero();
    }

    @Test
    @DisplayName("캘린더 정렬 무관 — 내부에서 최신순 처리")
    void streak_unsortedCalendar() {
        List<LocalDate> unsorted = List.of(D(1), LocalDate.of(2026, 6, 30), D(6), D(2), D(3));
        assertThat(calc(unsorted, Set.of(D(6), D(3)))).isEqualTo(2);   // 7/6,7/3 → 7/2 매수 아님
    }
}
